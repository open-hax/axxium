"""Native-origin controller laws; synthetic fixtures supply no qualification credit."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch

import native_campaign_job as native
import test_collect_mutation_evidence as fixtures


class NativeJobLaws(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='axxium-native-job-law-')
        self.addCleanup(self.temporary.cleanup)
        self.base=Path(self.temporary.name)
        self.root=self.base/'worker2';self.root.mkdir()
        self.proc=self.base/'proc';self.proc.mkdir()
        self.groups=self.base/'cgroup';self.groups.mkdir()
        self.unit=native.unit_name(2)
        self.gate=self.root/'qualification/results/.gate'
        self.properties={'ActiveState':'active','SubState':'running','MainPID':'123',
            'ControlGroup':'/system.slice/'+self.unit,'InvocationID':'fixture-invocation',
            'MemoryMax':str(5*1024**3),'MemoryHigh':str(4*1024**3),'MemorySwapMax':'0',
            'CPUQuotaPerSecUSec':'4s','TasksMax':'192','KillMode':'control-group','OOMPolicy':'stop'}
        self.group=self.groups/'system.slice'/self.unit;self.group.mkdir(parents=True)
        for key,value in {'memory.max':str(5*1024**3),'memory.high':str(4*1024**3),'memory.swap.max':'0',
                          'cpu.max':'400000 100000','pids.max':'192','cgroup.procs':'123'}.items():
            (self.group/key).write_text(value)
        process=self.proc/'123';process.mkdir();(process/'cwd').symlink_to(self.root,target_is_directory=True)
        argv=['/bin/bash','--noprofile','--norc','-c',native.GATE,'qualification-native-cap-gate',str(self.gate),*native.cli(self.root,2)]
        (process/'cmdline').write_bytes(b'\0'.join(part.encode() for part in argv)+b'\0')
        self.environment={'HOME':'/fixture/home','PATH':'/fixture/node:/usr/bin:/bin'}
        (process/'environ').write_bytes(b'\0'.join((k+'='+v).encode() for k,v in self.environment.items())+b'\0')

    def command(self, argv):
        self.assertEqual(argv[:3],['sudo','systemctl','show'])
        return '\n'.join(k+'='+v for k,v in self.properties.items())

    def witness(self):
        return native.active_caps(self.unit,self.root,self.gate,self.environment,2,
            command=self.command,proc=self.proc,cgroups=self.groups)

    def finish(self):
        for path in (self.proc/'123').iterdir():path.unlink()
        (self.proc/'123').rmdir();(self.group/'cgroup.procs').write_text('')
        self.properties.update(ActiveState='inactive',SubState='dead',MainPID='0')

    def test_exact_whole_partition_cli_has_no_resume_ack_exclusion_or_wrapper(self):
        for index,(count,digest) in native.PARTITIONS.items():
            args=native.cli(self.root,index)
            self.assertEqual(args[:4],['/usr/bin/python3','-B','-u','qualification/mutation_campaign.py'])
            self.assertEqual(args[args.index('--batch-index')+1],str(index))
            self.assertEqual(args[args.index('--profile')+1],'standard-v2')
            self.assertEqual(args[args.index('--sample-seed')+1],native.collector.SEED)
            self.assertEqual(args[args.index('--external-harness-sha')+1],native.collector.HARNESS)
            self.assertEqual(args[args.index('--producer-revision')+1],native.collector.PRODUCER)
            self.assertEqual(args[args.index('--limit')+1],'1000')
            self.assertEqual(len(digest),64);self.assertGreater(count,700)
            self.assertFalse(any(flag in args for flag in ['--continue-from','--acknowledge-containment','--restore-from']))

    def test_non_home_volta_install_survives_the_stripped_runtime_boundary(self):
        home=Path('/synthetic/home');volta_home=Path('/synthetic/hosted-cache/volta/2.0.2')
        volta=volta_home/'bin/volta';node=volta_home/'tools/image/node/22.23.3/bin/node'
        with patch.dict(os.environ,{'GH_TOKEN':'synthetic-secret','GITHUB_TOKEN':'synthetic-secret'}):
            environment=native.runtime_environment(node,volta,volta_home,self.base,home)
        self.assertEqual(environment['VOLTA_HOME'],str(volta_home))
        self.assertIn(str(volta.parent),environment['PATH'].split(':'))
        self.assertNotIn(str(home/'.volta/bin'),environment['PATH'].split(':'))
        self.assertNotIn('GH_TOKEN',environment);self.assertNotIn('GITHUB_TOKEN',environment)
        calls=[]
        def probe(argv,**options):
            calls.append((argv,options))
            return '2.0.2' if argv==['volta','--version'] else str(node) if '-p' in argv else 'v22.23.3'
        result=native.verify_runtime(environment,node,self.root,command=probe)
        self.assertTrue(result['canonical_lookup_verified_before_gate'])
        self.assertEqual(calls[1][0],['volta','run','--node','22.23.3','node','-p','process.execPath'])
        self.assertTrue(all(options=={'cwd':self.root,'env':environment} for _,options in calls))
        with self.assertRaises(ValueError):native.verify_runtime(environment,node,self.root,command=lambda *a,**k:'wrong-tool')
        def another_node(argv,**options):return '2.0.2' if argv==['volta','--version'] else '/another/node'
        with self.assertRaises(ValueError):native.verify_runtime(environment,node,self.root,command=another_node)
        self.assertFalse(self.gate.exists())

    def test_public_runtime_refusal_has_a_fixed_stage_without_private_input(self):
        private='https://private.invalid/private-runtime-payload'
        result=subprocess.run([sys.executable,'-B',native.__file__,'--partition','2'],
            env={'HOME':str(self.base),'PATH':os.defpath,'LANG':'C.UTF-8','GITHUB_REPOSITORY':private},
            capture_output=True,text=True,check=False)
        self.assertEqual(result.returncode,1)
        value=json.loads(result.stdout)
        self.assertEqual(value['stage'],'native-context');self.assertEqual(value['error_type'],'ValueError')
        self.assertFalse(value['production_admitted'])
        self.assertNotIn(private,result.stdout+result.stderr);self.assertNotIn('Traceback',result.stderr)

    def test_caps_must_be_actual_active_and_match_every_native_limit(self):
        witness=self.witness();self.assertEqual(witness['properties']['MainPID'],'123')
        for key,value in [('ActiveState','inactive'),('MemoryMax','999'),('TasksMax','193'),('MemorySwapMax','1'),('InvocationID','')]:
            with self.subTest(key=key),patch.dict(self.properties,{key:value}):
                with self.assertRaises(ValueError):self.witness()
        (self.group/'cpu.max').write_text('max 100000')
        with self.assertRaises(ValueError):self.witness()

    def test_wrong_cli_extra_token_and_wrong_cgroup_refuse_before_release(self):
        process=self.proc/'123';old=(process/'cmdline').read_bytes()
        (process/'cmdline').write_bytes(old+b'extra\0')
        with self.assertRaises(ValueError):self.witness()
        (process/'cmdline').write_bytes(old)
        (process/'environ').write_bytes(b'GH_TOKEN=fixture-secret\0')
        with self.assertRaises(ValueError):self.witness()
        (process/'environ').write_bytes(b'\0'.join((k+'='+v).encode() for k,v in self.environment.items())+b'\0')
        with patch.dict(self.properties,ControlGroup='/user.slice/forged.service'):
            with self.assertRaises(ValueError):self.witness()
        self.assertFalse(self.gate.exists())

    def test_actual_native_bash_gate_exposes_exact_command_and_stripped_environment(self):
        argv=['/bin/bash','--noprofile','--norc','-c',native.GATE,'qualification-native-cap-gate',str(self.gate),*native.cli(self.root,2)]
        process=subprocess.Popen(['/usr/bin/env','-i',*[k+'='+v for k,v in self.environment.items()],*argv],cwd=self.root)
        try:
            expected=b'\0'.join(part.encode() for part in argv)+b'\0'
            path=Path('/proc')/str(process.pid)/'cmdline'
            for _ in range(100):
                if path.read_bytes()==expected:break
                time.sleep(.01)
            self.properties['MainPID']=str(process.pid)
            (self.group/'cgroup.procs').write_text(str(process.pid))
            result=native.active_caps(self.unit,self.root,self.gate,self.environment,2,command=self.command,cgroups=self.groups)
            self.assertEqual(result['gate_argv'],argv)
            self.assertEqual(result['minimal_environment'],self.environment)
            self.assertFalse(self.gate.exists())
        finally:
            process.terminate();process.wait(timeout=5)

    def test_closure_binds_prior_active_invocation_and_original_controller_absence(self):
        witness=self.witness()
        with self.assertRaises(ValueError):native.closed(self.unit,witness=None,command=self.command,cgroups=self.groups,proc=self.proc)
        with self.assertRaises(ValueError):native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)
        self.finish()
        result=native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)
        self.assertEqual(result['bus'],'system');self.assertTrue(result['original_controller_absent'])
        with patch.dict(self.properties,InvocationID='another-invocation'):
            with self.assertRaises(ValueError):native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)

    def test_closure_refuses_descendant_processes_and_traversal_errors(self):
        witness=self.witness();self.finish()
        child=self.group/'child';child.mkdir();(child/'cgroup.procs').write_text('456')
        with self.assertRaises(ValueError):native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)
        (child/'cgroup.procs').write_text('')
        def unreadable(path,*,onerror):onerror(PermissionError('synthetic traversal refusal'))
        with patch.object(native.os,'walk',unreadable):
            with self.assertRaises(PermissionError):native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)
        (child/'cgroup.procs').unlink()
        with self.assertRaises(FileNotFoundError):native.closed(self.unit,witness=witness,command=self.command,cgroups=self.groups,proc=self.proc)

    def test_native_dispatch_identity_is_bound_before_source_effects(self):
        controller=self.base/'controller';(controller/'.github/workflows').mkdir(parents=True);(controller/'qualification').mkdir()
        (controller/native.WORKFLOW).write_text('synthetic source fixture')
        (controller/'qualification/native_campaign_job.py').write_text('synthetic controller fixture')
        def git(*args):return subprocess.check_output(['git',*args],cwd=controller,text=True).strip()
        git('init','-q');git('add','.');git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture')
        sha=git('rev-parse','HEAD')
        env={'GITHUB_REPOSITORY':'open-hax/axxium','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main',
            'GITHUB_JOB':'partition','RUNNER_ENVIRONMENT':'github-hosted','GITHUB_SHA':sha,'GITHUB_RUN_ID':'7',
            'GITHUB_RUN_ATTEMPT':'2','RUNNER_NAME':'synthetic native runner'}
        fixture={'id':7,'run_attempt':2,'event':'workflow_dispatch','head_branch':'main','head_sha':sha,
            'path':native.WORKFLOW,'status':'in_progress','repository':{'full_name':'open-hax/axxium'},
            'head_repository':{'full_name':'open-hax/axxium'}}
        job={'id':8,'name':'Native partition 2','runner_name':env['RUNNER_NAME'],'status':'in_progress','run_id':7,'head_sha':sha}
        values={'actions/runs/7/attempts/2':fixture,'compare/'+sha+'...main':{'merge_base_commit':{'sha':sha}},
            'actions/runs/7/attempts/2/jobs?per_page=100':{'total_count':1,'jobs':[job]}}
        calls=[]
        def api(endpoint):calls.append(endpoint);return copy.deepcopy(values[endpoint])
        with patch.object(native,'CONTROLLER',controller):
            origin=native.context(2,environment=env,api=api,command=lambda argv:git(*argv[1:]))
            self.assertEqual((origin['run_id'],origin['run_attempt'],origin['job_id']),(7,2,8))
            for key,value in [('GITHUB_REF','refs/heads/not-main'),('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_JOB','other')]:
                with self.subTest(key=key):
                    with self.assertRaises(ValueError):native.context(2,environment={**env,key:value},api=api,command=lambda argv:sha)
            for field in ('head_sha','run_attempt','path','head_branch'):
                saved=fixture[field];fixture[field]='wrong'
                with self.assertRaises(ValueError):native.context(2,environment=env,api=api,command=lambda argv:sha)
                fixture[field]=saved
            job['runner_name']='another runner'
            with self.assertRaises(ValueError):native.context(2,environment=env,api=api,command=lambda argv:sha)
        self.assertFalse((self.base/'worker3').exists())

    def test_snapshot_preserves_all_raw_files_and_hashes_without_following_source_links(self):
        root=self.root;subprocess.run(['git','init','-q'],cwd=root,check=True)
        (root/'application.txt').write_text('synthetic application fixture')
        subprocess.run(['git','add','.'],cwd=root,check=True)
        subprocess.run(['git','-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture'],cwd=root,check=True)
        results=root/'qualification/results';results.mkdir(parents=True);(results/'report.json').write_text('{"status":"unqualified"}')
        (results/'negative.test.log').write_text('synthetic error retained')
        harness=root/'qualification/harness/fixture';harness.mkdir(parents=True);(harness/'source.cljs').symlink_to(root/'application.txt')
        (harness/'target').mkdir();(harness/'target/cache').write_text('omitted compiler output')
        for name in native.collector.RUNTIME_FILES:(root/'qualification'/name).write_text('synthetic runtime fixture')
        out=self.base/'artifact';out.mkdir();(out/'origin.json').write_text('{"native_run_fixture":true}')
        native.snapshot(root,out,{'fixture':'zero qualification credit'})
        manifest=json.loads((out/'artifact-manifest.json').read_text())
        for name,digest in manifest['files'].items():self.assertEqual(native.file_sha(out/name),digest)
        self.assertIn('qualification/results/negative.test.log',manifest['raw_members'])
        self.assertEqual(manifest['raw_members']['qualification/harness/fixture/source.cljs']['symlink'],str(root/'application.txt'))
        with tarfile.open(out/'raw-evidence.tar') as archive:
            self.assertTrue(archive.getmember('qualification/harness/fixture/source.cljs').issym())
            self.assertNotIn('qualification/harness/fixture/target/cache',archive.getnames())
        self.assertFalse(manifest['production_admitted'])

    def test_controller_failure_preserves_native_negative_artifact_without_acknowledgement(self):
        controller=self.base/'controller';controller.mkdir();base=self.base/'base';base.mkdir()
        origin={'run_id':7,'run_attempt':2,'job_id':8,'controller_sha':'f'*40,'partition':2}
        real_run=subprocess.run
        def failing_execute(index,root,unit,destination,bound_origin,adoption,state):
            root.mkdir()
            real_run(['git','init','-q'],cwd=root,check=True)
            (root/'source').write_text('synthetic source fixture')
            real_run(['git','add','.'],cwd=root,check=True)
            real_run(['git','-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture'],cwd=root,check=True)
            results=root/'qualification/results';results.mkdir(parents=True)
            (results/'negative.log').write_text('synthetic prelaunch negative retained')
            raise RuntimeError('https://private.invalid/private-payload')
        def journal(argv,**kwargs):
            if argv[0]=='git':return real_run(argv,**kwargs)
            self.assertEqual(argv[:3],['sudo','journalctl','--unit='+self.unit])
            kwargs['stdout'].write(b'synthetic native journal retained')
        with patch.object(native,'CONTROLLER',controller),patch.object(native,'BASE',base),             patch.object(native,'context',return_value=origin),patch.object(native.collector,'verify_adoption',return_value={'fixture':True}),             patch.object(native,'execute',side_effect=failing_execute),             patch.object(native,'system_properties',return_value={'ActiveState':'inactive','MainPID':'0'}),             patch.object(native.subprocess,'run',side_effect=journal),patch.object(native.sys,'argv',['native','--partition','2']):
            with self.assertRaises(RuntimeError):native.main()
        destination=controller/'native-evidence-2'
        refusal=json.loads((destination/'controller-refusal.json').read_text())
        self.assertEqual(refusal['error_type'],'RuntimeError');self.assertFalse(refusal['self_acknowledged'])
        self.assertNotIn('private-payload',(destination/'controller-refusal.json').read_text())
        closure=json.loads((destination/'origin-closure.json').read_text())
        self.assertTrue(closure['closure']['no_active_invocation_witness'])
        manifest=json.loads((destination/'artifact-manifest.json').read_text())
        self.assertIn('qualification/results/negative.log',manifest['raw_members'])
        self.assertIn('controller-refusal.json',manifest['files'])
        self.assertFalse((destination/'partition.json').exists())

    def test_closure_observation_refusal_still_preserves_raw_negative_artifact(self):
        root=self.root
        subprocess.run(['git','init','-q'],cwd=root,check=True)
        source=root/'src/cljs/example.cljc';source.parent.mkdir(parents=True)
        source.write_text('(def value 1)\n')
        subprocess.run(['git','add','.'],cwd=root,check=True)
        subprocess.run(['git','-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture'],cwd=root,check=True)
        source.write_text('(def value 2)\n')
        untracked=source.parent/'untracked.cljc';untracked.write_text('(def untracked true)\n')
        results=root/'qualification/results';results.mkdir(parents=True)
        (results/'negative.log').write_text('synthetic raw negative survives failed closure observation')
        out=self.base/'closure-failed-artifact';out.mkdir()
        real_run=subprocess.run
        def journal(argv,**kwargs):
            if argv[0]=='git':return real_run(argv,**kwargs)
            kwargs['stdout'].write(b'synthetic native journal after failed closure')
        with patch.object(native,'system_properties',side_effect=PermissionError('https://private.invalid/payload')),             patch.object(native.subprocess,'run',side_effect=journal):
            with self.assertRaises(ValueError):native.preserve(root,out,{'synthetic':'no credit'},self.unit,{})
        refusal=json.loads((out/'origin-closure-refusal.json').read_text())
        self.assertEqual(refusal['error_type'],'PermissionError');self.assertFalse(refusal['closure_verified'])
        self.assertNotIn('private.invalid',(out/'origin-closure-refusal.json').read_text())
        manifest=json.loads((out/'artifact-manifest.json').read_text())
        self.assertEqual(manifest['status'],'unqualified_snapshot')
        self.assertFalse(manifest['origin_quiescence_verified'])
        self.assertIn('qualification/results/negative.log',manifest['raw_members'])
        self.assertEqual(manifest['raw_members']['src/cljs/example.cljc']['sha256'],native.file_sha(source))
        self.assertIn('src/cljs/untracked.cljc',manifest['raw_members'])
        with tarfile.open(out/'raw-evidence.tar') as archive:
            self.assertEqual(archive.extractfile('src/cljs/example.cljc').read(),b'(def value 2)\n')
            self.assertEqual(archive.extractfile('src/cljs/untracked.cljc').read(),b'(def untracked true)\n')
        restored=self.base/'committed-source'
        subprocess.run(['git','clone','-q',str(out/'source.bundle'),str(restored)],check=True,capture_output=True)
        self.assertEqual((restored/'src/cljs/example.cljc').read_bytes(),b'(def value 1)\n')
        self.assertIn('origin-closure-refusal.json',manifest['files'])
        self.assertTrue((out/'native-unit.log').exists());self.assertFalse((out/'partition.json').exists())

    def test_workflow_is_manual_readonly_pinned_and_retains_failed_jobs(self):
        workflow=(native.CONTROLLER/native.WORKFLOW).read_text()
        self.assertIn('workflow_dispatch:',workflow)
        self.assertNotIn('pull_request:',workflow);self.assertNotIn('schedule:',workflow)
        self.assertIn('partition: [2, 3]',workflow);self.assertIn('fail-fast: false',workflow)
        self.assertNotIn(': write',workflow);self.assertNotIn('secrets.',workflow)
        self.assertIn('contents: read',workflow);self.assertIn('actions: read',workflow)
        self.assertIn('pull-requests: read',workflow);self.assertIn('if: always()',workflow)
        self.assertIn('artifact-digest',workflow);self.assertIn('include-hidden-files: true',workflow)
        self.assertIn('fetch-depth: 0',workflow);self.assertIn('persist-credentials: false',workflow)
        self.assertIn('GH_TOKEN: ${{ github.token }}',workflow);self.assertIn('GITHUB_TOKEN: ${{ github.token }}',workflow)
        for line in workflow.splitlines():
            if 'uses:' in line:self.assertRegex(line,r'@[0-9a-f]{40}(?:\s|$)')


class NativeProgressLaws(unittest.TestCase):
    """Reuse real temporary-Git/raw-log fixtures; never actual mutation evidence."""
    @classmethod
    def setUpClass(cls):fixtures.CollectionTests.setUpClass.__func__(cls)
    def setUp(self):fixtures.CollectionTests.setUp(self)
    write=fixtures.CollectionTests.write
    output=fixtures.CollectionTests.output
    baseline=fixtures.CollectionTests.baseline
    report=fixtures.CollectionTests.report

    def test_monitor_calls_native_raw_classifier_and_accepted_guard(self):
        entry,data=self.report(2,['killed','test_error','survived'])
        data['baseline']={'status':'passed'};self.write(Path(entry['report']),data)
        with native.collector.worker_context(self.root):
            candidates,selection=native.collector.lineage.select_candidates(native.collector.campaign,profile=native.collector.PROFILE,
                path_prefix='src/cljs/',batch_index=2,sample_seed=native.collector.SEED)
        result=native.progress(self.root,Path(entry['report']),candidates,selection,data['producer'],self.harness)
        self.assertEqual((result['observed'],result['eligible']),(3,1))
        data['results'][0]['test_exit']=0
        self.write(Path(entry['report']),data)
        with self.assertRaises(ValueError):native.progress(self.root,Path(entry['report']),candidates,selection,data['producer'],self.harness)


if __name__=='__main__':unittest.main()
