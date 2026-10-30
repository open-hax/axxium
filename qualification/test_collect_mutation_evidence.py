"""Explicit synthetic execution fixtures exercise collection, never actual qualification."""
import copy
import contextlib
import hashlib
import json
import io
import os
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest
from unittest import mock

import collect_mutation_evidence as collect
import test_phase as phase
from test_test_phase import node_binary


class CollectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = tempfile.TemporaryDirectory(prefix='axxium-collection-template-')
        cls.addClassCleanup(cls.template.cleanup)
        cls.template_root = Path(cls.template.name)
        runtime=cls.template_root/'qualification';runtime.mkdir()
        for name in collect.RUNTIME_FILES:
            (runtime/name).write_bytes((collect.HERE/name).read_bytes())
        source = cls.template_root/'src/cljs/axxium/example.cljc';source.parent.mkdir(parents=True)
        source.write_text('(ns axxium.example)\n(def values [' + ' '.join(['111']*600) + '])\n')
        (cls.template_root/'package-lock.json').write_text('{"lockfileVersion":3}\n')
        for args in (['init','-q'], ['config','user.name','Fixture'],
                     ['config','user.email','fixture@example.invalid'], ['config','core.hooksPath','/dev/null'],
                     ['add','qualification'], ['commit','--no-gpg-sign','-qm','synthetic producer fixture']):
            subprocess.run(['git',*args],cwd=cls.template_root,check=True,capture_output=True)
        cls.producer=subprocess.check_output(['git','rev-parse','HEAD'],cwd=cls.template_root,text=True).strip()
        for args in (['rm','--cached','-r','qualification'],['add','src','package-lock.json'],
                     ['commit','--no-gpg-sign','-qm','synthetic source fixture']):
            subprocess.run(['git',*args],cwd=cls.template_root,check=True,capture_output=True)
        cls.application = subprocess.check_output(['git','rev-parse','HEAD'],cwd=cls.template_root,text=True).strip()
        support=cls.template_root/'scripts/oidc-protocol.fixture.mjs';support.parent.mkdir()
        support.write_bytes((collect.HERE.parent/'scripts/oidc-protocol.fixture.mjs').read_bytes())
        for args in (['add','scripts'],['commit','--no-gpg-sign','-qm','synthetic pure harness fixture']):
            subprocess.run(['git',*args],cwd=cls.template_root,check=True,capture_output=True)
        cls.harness_revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=cls.template_root,text=True).strip()
        subprocess.run(['git','checkout','-q','--detach',cls.application],cwd=cls.template_root,check=True,capture_output=True)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='axxium-collection-fixture-');self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)/'worker'
        subprocess.run(['git','clone','--shared','-q',str(self.template_root),str(self.root)],check=True,capture_output=True)
        for name,value in [('APPLICATION',self.application),('PRODUCER',self.producer),('HARNESS',self.harness_revision)]:
            patch=mock.patch.object(collect,name,value);patch.start();self.addCleanup(patch.stop)
        self.results = self.root/'qualification/results';self.results.mkdir(parents=True,mode=0o700)
        self.results.parent.chmod(0o700)
        self.write(self.results/'.campaign.lock',b'')
        for name in collect.RUNTIME_FILES:
            self.write(self.root/'qualification'/name,(collect.HERE/name).read_bytes())
        self.harness = {'harness_sha':collect.HARNESS,'scope':'EXPLICIT SYNTHETIC UNIT FIXTURE, NOT EVIDENCE'}
        self.write(self.root/'qualification/harness'/collect.HARNESS/collect.external_harness.DESCRIPTOR,self.harness)
        patch=mock.patch.object(collect,'HERE',self.root/'qualification');patch.start();self.addCleanup(patch.stop)
        for module,name in [(collect.campaign,'mutation_campaign.py'),(collect.lineage,'campaign_lineage.py'),
                            (collect.external_harness,'external_harness.py'),(collect.test_phase,'test_phase.py')]:
            patch=mock.patch.object(module,'__file__',str(self.root/'qualification'/name))
            patch.start();self.addCleanup(patch.stop)
        patch = mock.patch.object(collect.external_harness,'validate');self.validate=patch.start();self.addCleanup(patch.stop)
        self.summary = {'schema':'axxium-qualification-test-phase/v1','plan':'cljs','tests':1,'assertions':1,
            'failures':0,'errors':0,'warnings':[],'timed_out':False,'containment_unknown':False,
            'groups':[{'name':'cljs','tests':1,'assertions':1,'failures':0,'errors':0,'warnings':[],'timed_out':False}]}
        self.build = b'Build completed. (0 warnings)\n'
        self.closed = lambda unit: {'unit':unit,'native':{'ActiveState':'inactive','MainPID':'0'},
                                    'cgroup_empty_or_absent':True,'scope':'synthetic native adapter fixture'}

    def write(self,path,value):
        path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        if isinstance(value,dict):value=collect.encoded(value)
        if isinstance(value,str):value=value.encode()
        path.write_bytes(value);path.chmod(0o600)
        return hashlib.sha256(value).hexdigest()

    def output(self,summary):
        return ('QUALIFICATION_TEST_SUMMARY='+json.dumps(summary)+'\n').encode()

    def baseline(self,directory,prefix):
        return {'tests':1,'assertions':1,'failures':0,'errors':0,'driver_plan':'cljs','driver_groups':['cljs'],
            'driver_summary':copy.deepcopy(self.summary),
            'compile_log_sha256':self.write(directory/(prefix+'.cljs.compile.log'),self.build),
            'test_log_sha256':self.write(directory/(prefix+'.cljs.test.log'),self.output(self.summary))}

    def report(self,index,statuses=None,*,aborted=False):
        statuses = statuses or ['killed']*250
        directory = self.results/('batch-'+str(index))
        with collect.worker_context(self.root):
            candidates,selection=collect.lineage.select_candidates(collect.campaign,profile=collect.PROFILE,
                path_prefix='src/cljs/',batch_index=index,sample_seed=collect.SEED)
            producer=collect.campaign.producer_custody(collect.PRODUCER,external=True)
        rows=[]
        for number,status in enumerate(statuses):
            summary=copy.deepcopy(self.summary)
            if status=='killed':summary['failures']=summary['groups'][0]['failures']=1
            if status=='test_error':summary['errors']=summary['groups'][0]['errors']=1
            timeout=status=='timeout'
            row={**candidates[number],'test_plan':'cljs','compile_status':'passed','phase':'test','status':status,
                'compile_exit':0,'compile_timed_out':False,'compile_output_overflow':False,'compile_containment_unknown':False,
                'test_exit':-9 if timeout else 1 if status in {'killed','test_error'} else 0,
                'test_timed_out':timeout,'test_output_overflow':False,'test_containment_unknown':timeout,
                'compile_log_sha256':self.write(directory/f'{number:04}.compile.log',self.build),
                'test_log_sha256':self.write(directory/f'{number:04}.test.log',self.output(summary)),
                'driver_summary':summary,'counts':{field:summary[field] for field in ('tests','assertions','failures','errors')}}
            rows.append(row)
        data={'scope':'SYNTHETIC EXECUTION FIXTURE, NO QUALIFICATION CREDIT',
            'source_sha':self.application,'application_under_test':self.application,'selection':selection,
            'producer':producer,'test_harness':self.harness,'operator_id':'fixture-controller',
            'batch_index':index,'sample_seed':collect.SEED,'observed_prefix_start':0,
            'observed_prefix_end':len(rows),'candidate_inventory':len(candidates),'execution_inventory':len(candidates),
            'completed_selection':False,'qualified_batch':False,'terminal_state':'aborted' if aborted else 'clean_partial',
            'containment':'unknown_after_aborted_execution' if aborted else 'not_proven',
            'baselines':{'cljs':self.baseline(directory,'baseline')},'results':rows,
            'test_plans':{'cljs':{'key':'cljs','compile_targets':['test'],'groups':['cljs']}},
            'candidate_test_plans':{row['fingerprint']:'cljs' for row in candidates},
            'unexecuted_fingerprints':[row['fingerprint'] for row in candidates[len(rows):]]}
        if not aborted:
            data.update(restored_baseline='passed',restored_baselines={'cljs':self.baseline(directory,'restored')})
        path=directory/'report.json';self.write(path,data)
        unit='axxium-qual582-standard-v2-v5-worker'+str(index%2)+('' if index<2 else '-batch'+str(index))+'.service'
        return {'batch_index':index,'worker_root':str(self.root),'report':str(path),'unit':unit},data

    def test_first_250_real_classifier_positives_preserve_negatives_full_tail_and_extra_kills(self):
        statuses=['survived','test_error']+['killed']*251+['survived']
        entry,data=self.report(0,statuses)
        original=Path(entry['report']).read_bytes()
        batch,audit=collect.collect_batch(entry,observe_unit=self.closed)
        self.assertEqual(collect.REQUIRED_KILLS,250)
        self.assertEqual(len(batch['mutants']),250)
        self.assertEqual(audit['selected_ordinals'],list(range(2,252)))
        self.assertEqual(audit['eligible_actual_kills'],251)
        self.assertEqual(audit['raw_outcome_counts'],{'killed':251,'survived':2,'test_error':1})
        self.assertEqual(len(audit['observations']),len(statuses))
        self.assertEqual(audit['unexecuted_fingerprints'],data['unexecuted_fingerprints'])
        self.assertIsNone(batch['score_claim'])
        self.assertEqual(Path(entry['report']).read_bytes(),original)

    def test_four_disjoint_250_collections_are_required_without_full_corpus_exhaustion(self):
        entries=[self.report(i)[0] for i in range(4)]
        batches,audits=collect.collect(entries,observe_unit=self.closed)
        self.assertEqual([batch['batch_index'] for batch in batches],[0,1,2,3])
        self.assertEqual(len({row['fingerprint'] for batch in batches for row in batch['mutants']}),1000)
        self.assertTrue(all(audit['unexecuted_fingerprints'] for audit in audits))
        for invalid in (entries[:3],[entries[0]]*4,[{**entries[0],'batch_index':False},*entries[1:]]):
            with self.assertRaises(collect.Refusal):collect.collect(invalid,observe_unit=self.closed)

    def test_a_deficit_survivor_error_or_unstructured_raw_kill_never_fills_the_threshold(self):
        entry,_=self.report(0,['killed']*249+['test_error','survived'])
        with self.assertRaisesRegex(collect.Refusal,'only 249'):collect.collect_batch(entry,observe_unit=self.closed)
        entry,data=self.report(1,['killed']*250)
        row=data['results'][-1];number=len(data['results'])-1
        legacy=b'FAIL in (unit-fixture)\nRan 1 tests containing 1 assertions.\n1 failures, 0 errors.\n'
        row['test_log_sha256']=self.write(Path(entry['report']).parent/f'{number:04}.test.log',legacy)
        row['driver_summary']=None
        self.write(Path(entry['report']),data)
        with self.assertRaisesRegex(collect.Refusal,'only 249'):collect.collect_batch(entry,observe_unit=self.closed)

    def test_error_observation_wrong_exit_warnings_and_boolean_counts_are_ineligible(self):
        _,data=self.report(0,['killed'])
        row=data['results'][0];plan=data['test_plans']['cljs']
        self.assertTrue(collect.eligible(row,plan))
        for field,value in [('test_exit',126),('compile_exit',False),('test_timed_out',True),('test_containment_unknown',True)]:
            changed=copy.deepcopy(row);changed[field]=value
            self.assertFalse(collect.eligible(changed,plan))
        for field,value in [('errors',1),('errors',False),('warnings',['warning']),('timed_out',True)]:
            changed=copy.deepcopy(row);changed['driver_summary'][field]=value
            changed['driver_summary']['groups'][0][field]=value
            self.assertFalse(collect.eligible(changed,plan))

    def protocol_control(self, *, after_authority=False, passing=False):
        with tempfile.TemporaryDirectory(prefix='aq-collection-control-') as temporary:
            root=Path(temporary);(root/'scripts').mkdir();counter=root/'counter';counter.mkdir()
            script=root/'scripts/oidc-protocol.fixture.mjs'
            markers=phase.PROTOCOL_MARKERS if passing else [phase.PROTOCOL_MARKERS[0]] if after_authority else []
            script.write_text("import assert from 'node:assert/strict';\n"+
                ''.join('console.log('+json.dumps(marker)+');\n' for marker in markers)+
                'assert.equal(1,'+('1' if passing else '2')+');\n')
            environment={'PATH':os.defpath,'HOME':str(root),'LANG':'C.UTF-8',
                'AXXIUM_QUAL_SOURCE_ROOT':str(root),'AXXIUM_QUAL_COUNTER_DIR':str(counter),
                'NODE_OPTIONS':'--max-old-space-size=1024 --import='+(phase.HERE/'assertion_measurement.mjs').as_uri()}
            prefixed=io.StringIO()
            with contextlib.redirect_stdout(prefixed):
                code,raw,_,_=phase.child([node_binary(),str(script)],root,environment,'protocol')
            return phase.protocol_result(code,raw,phase.counters(counter)),prefixed.getvalue()

    def test_native_raw_prefix_scope_controls_before_after_and_mixed_group_credit(self):
        _,data=self.report(0,['killed']);row=data['results'][0]
        plan={'key':'cljs+protocol','groups':['cljs','protocol']}
        def result(protocol,cljs_failures):
            cljs={**self.summary['groups'][0],'failures':cljs_failures}
            groups=[cljs,{'name':'protocol',**protocol}]
            summary={'schema':'axxium-qualification-test-phase/v1','plan':plan['key'],'groups':groups,
                'containment_unknown':False,'warnings':[],'timed_out':False,
                **{field:sum(g[field] for g in groups) for field in ('tests','assertions','failures','errors')}}
            return {**row,'driver_summary':summary}
        for after in (False,True):
            protocol,raw=self.protocol_control(after_authority=after)
            self.assertEqual((protocol['failures'],protocol['errors']),(1,0))
            candidate=result(protocol,0)
            output=raw+'QUALIFICATION_TEST_SUMMARY='+json.dumps(candidate['driver_summary'])
            baseline={'tests':2,'assertions':2,'driver_plan':plan['key'],'driver_groups':plan['groups']}
            self.assertEqual(collect.campaign.classify_test({'exit_code':1,'timed_out':False,'output':output},baseline),'killed')
            self.assertEqual(collect.eligible(candidate,plan,output),not after)
            if after:
                self.assertEqual(collect.eligibility_exclusion(candidate,plan,output),
                                 'protocol-failure-after-authority-observer-scope')
                self.assertFalse(collect.eligible(result(protocol,1),plan,output))
            else:
                wrong_prefix='QUALIFICATION_CHILD[cljs] '+collect.AUTHORITY_DONE+' fixture data\n'
                self.assertTrue(collect.eligible(candidate,plan,wrong_prefix+output))
        protocol,raw=self.protocol_control(passing=True)
        self.assertEqual((protocol['failures'],protocol['errors']),(0,0))
        self.assertTrue(collect.eligible(result(protocol,1),plan,raw))

    def test_raw_log_report_source_runtime_and_harness_drift_fail_closed(self):
        entry,data=self.report(0)
        path=Path(entry['report']).parent/'0000.test.log';original=path.read_bytes();self.write(path,b'changed raw log')
        with self.assertRaises(ValueError):collect.collect_batch(entry,observe_unit=self.closed)
        self.write(path,original)
        runtime=self.root/'qualification/assertion_measurement.mjs';original=runtime.read_bytes();self.write(runtime,b'changed runtime')
        with self.assertRaises(collect.Refusal):collect.collect_batch(entry,observe_unit=self.closed)
        self.write(runtime,original)
        source=self.root/'src/cljs/axxium/example.cljc';original=source.read_bytes();source.write_bytes(original+b'\n')
        with self.assertRaises(ValueError):collect.collect_batch(entry,observe_unit=self.closed)
        source.write_bytes(original)
        descriptor=self.root/'qualification/harness'/collect.HARNESS/collect.external_harness.DESCRIPTOR
        self.write(descriptor,{**self.harness,'harness_sha':'f'*40})
        with self.assertRaises(collect.Refusal):collect.collect_batch(entry,observe_unit=self.closed)

    def test_active_native_unit_or_exclusive_canonical_lock_blocks_collection(self):
        entry,_=self.report(0)
        with self.assertRaises(collect.Refusal):
            collect.unit_closed(entry['unit'],execute=lambda argv:'ActiveState=active\nMainPID=123\nControlGroup=\n')
        lock=os.open(self.results/'.campaign.lock',os.O_RDONLY)
        try:
            collect.fcntl.flock(lock,collect.fcntl.LOCK_EX|collect.fcntl.LOCK_NB)
            with self.assertRaises(collect.Refusal):
                with collect.read_leases([self.root]):pass
        finally:os.close(lock)
        with collect.read_leases([self.root]):
            with self.assertRaises(BlockingIOError):
                other=os.open(self.results/'.campaign.lock',os.O_RDONLY)
                try:collect.fcntl.flock(other,collect.fcntl.LOCK_EX|collect.fcntl.LOCK_NB)
                finally:os.close(other)

    def test_continuation_unit_names_are_bounded_and_require_native_inactivity(self):
        observation=lambda argv:'LoadState=not-found\nActiveState=inactive\nMainPID=0\nControlGroup=\n'
        prefix='axxium-qual582-standard-v2-v5-worker0'
        for suffix in ('-continue1','-batch2-continue12','-batch3-continue999'):
            self.assertTrue(collect.unit_closed(prefix+suffix+'.service',execute=observation)['cgroup_empty_or_absent'])
        for suffix in ('-continue0','-continue01','-continue1000','-continue-1','-batch0-continue1'):
            with self.assertRaises(collect.Refusal):collect.unit_closed(prefix+suffix+'.service',execute=observation)

    def test_aborted_terminal_requires_bound_ack_and_fresh_canonical_restoration(self):
        entry,data=self.report(0,['killed']*250+['timeout'],aborted=True)
        with self.assertRaises(KeyError):collect.collect_batch(entry,observe_unit=self.closed)
        report=Path(entry['report']);ack=self.results/'containment-ack-200.json'
        self.write(ack,{'marker':{'source_sha':self.application,'state':'execution_active_or_containment_unknown',
            'operator_pid':123,'started_ns':100,'logfile':str(report.parent/'0250.test.log')},
            'inspection_author':'fixture-independent-inspector','inspection_note':'Explicit unit fixture declaration, no real inspection evidence.',
            'acknowledged_ns':200,'acknowledger_pid':456,'attestation':'operator assertion, not independently verified'})
        restore=self.results/'restore/restoration.json'
        self.write(restore,{'schema':'axxium-campaign-restoration/v1','source_sha':self.application,
            'selection':data['selection'],'producer':data['producer'],'test_harness':self.harness,
            'prior_report':{'path':str(report),'sha256':hashlib.sha256(report.read_bytes()).hexdigest(),'next_index':251},
            'containment_ack':{'path':str(ack),'sha256':hashlib.sha256(ack.read_bytes()).hexdigest()},
            'status':'passed','warnings':[],'timed_out':False,'started_ns':201,'completed_ns':300,
            'baselines':{'cljs':self.baseline(restore.parent,'restore')}})
        entry.update(containment_ack=str(ack),restoration=str(restore))
        batch,audit=collect.collect_batch(entry,observe_unit=self.closed)
        self.assertEqual(len(batch['mutants']),250)
        self.assertEqual(audit['raw_outcome_counts'],{'killed':250,'timeout':1})
        self.assertEqual(set(audit['terminal_closure']),{'containment_ack','restoration'})

    def test_cli_writes_private_complete_bundle_last_and_refuses_a_deficit_before_output(self):
        entries=[self.report(i)[0] for i in range(4)]
        inputs=self.root.parent/'inputs.json'
        self.write(inputs,{'schema':'axxium-collection-inputs/v1','batches':entries})
        output=self.root.parent/'output'
        real_collect,real_write=collect.collect,collect.write_new
        writes=[]
        def record(path,raw):
            writes.append(path.name);real_write(path,raw)
        def invoke(destination):
            with mock.patch.object(sys,'argv',['collector','--inputs',str(inputs),
                    '--collector-revision','c'*40,'--out',str(destination)]), contextlib.redirect_stdout(io.StringIO()):
                collect.main()
        with mock.patch.object(collect,'verify_adoption',return_value={'scope':'EXPLICIT UNIT FIXTURE, NOT ADOPTION'}), \
                mock.patch.object(collect,'collect',side_effect=lambda rows:real_collect(rows,observe_unit=self.closed)), \
                mock.patch.object(collect,'write_new',side_effect=record):
            invoke(output)
            self.assertEqual(writes,['audit.json','mutation-batches.json','manifest.json'])
            manifest=json.loads((output/'manifest.json').read_text())
            self.assertEqual(manifest['status'],'complete')
            self.assertFalse(manifest['production_admitted'])
            for name,digest in manifest['files'].items():
                self.assertEqual(hashlib.sha256((output/name).read_bytes()).hexdigest(),digest)
                self.assertEqual((output/name).stat().st_mode&0o777,0o600)
            self.assertEqual(output.stat().st_mode&0o777,0o700)
            receipt=json.loads((output/'mutation-batches.json').read_text())
            self.assertLess(len((output/'mutation-batches.json').read_bytes()),768*1024)
            self.assertTrue(receipt['publication_and_services_admission_required'])
            entry,_=self.report(3,['killed']*249)
            entries[3]=entry;self.write(inputs,{'schema':'axxium-collection-inputs/v1','batches':entries})
            rejected=self.root.parent/'deficit'
            with self.assertRaises(SystemExit):invoke(rejected)
            self.assertFalse(rejected.exists())


class NativeAdoptionTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='axxium-collector-custody-');self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name);here=self.root/'qualification';here.mkdir()
        for name in collect.RUNTIME_FILES:
            (here/name).write_bytes((collect.HERE/name).read_bytes())
        for args in (['init','-q'],['config','user.name','Fixture'],['config','user.email','fixture@example.invalid'],
                     ['config','core.hooksPath','/dev/null'],['add','qualification'],
                     ['commit','--no-gpg-sign','-qm','explicit evaluator custody fixture']):
            subprocess.run(['git',*args],cwd=self.root,check=True,capture_output=True)
        producer=subprocess.check_output(['git','rev-parse','HEAD'],cwd=self.root,text=True).strip()
        (here/Path(collect.__file__).name).write_bytes(Path(collect.__file__).read_bytes())
        for args in (['add','qualification'],['commit','--no-gpg-sign','-qm','explicit collector custody fixture']):
            subprocess.run(['git',*args],cwd=self.root,check=True,capture_output=True)
        self.revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=self.root,text=True).strip()
        patch=mock.patch.object(collect,'HERE',here);patch.start();self.addCleanup(patch.stop)
        patch=mock.patch.object(collect,'PRODUCER',producer);patch.start();self.addCleanup(patch.stop)
        for module,name in [(collect.campaign,'mutation_campaign.py'),(collect.lineage,'campaign_lineage.py'),
                            (collect.external_harness,'external_harness.py'),(collect.test_phase,'test_phase.py')]:
            patch=mock.patch.object(module,'__file__',str(here/name));patch.start();self.addCleanup(patch.stop)
        self.calls=[]
        self.data={
            'pulls/42':self.pr(42,collect.PRODUCER,collect.PRODUCER_MERGE),
            'compare/'+collect.PRODUCER+'...main':{'merge_base_commit':{'sha':collect.PRODUCER}},
            'compare/'+collect.HARNESS+'...'+collect.PRODUCER:{'merge_base_commit':{'sha':collect.HARNESS}},
            # This real GitHub endpoint returns simple PRs without `merged`.
            'commits/'+self.revision+'/pulls?per_page=100':[{'number':99,'head':{'sha':self.revision}}],
            'pulls/99':self.pr(99,self.revision,'d'*40),
            'compare/'+self.revision+'...main':{'merge_base_commit':{'sha':self.revision}}}

    def pr(self,number,head,merge):
        return {'number':number,'merged':True,'merge_commit_sha':merge,
                'base':{'ref':'main','repo':{'full_name':collect.REPOSITORY}},
                'head':{'sha':head,'repo':{'full_name':collect.REPOSITORY}}}

    def api(self,endpoint):
        self.calls.append(endpoint);return copy.deepcopy(self.data[endpoint])

    def test_actual_git_bytes_and_full_native_pr_reads_bind_collector_producer_and_pure_ancestry(self):
        proof=collect.verify_adoption(self.revision,api=self.api)
        self.assertTrue(proof['native_adoption_verified'])
        self.assertEqual(proof['collector_pr'],99)
        self.assertIn('pulls/99',self.calls)
        self.assertEqual(proof['collector_sha256'],hashlib.sha256(Path(collect.__file__).read_bytes()).hexdigest())

    def test_unmerged_or_wrong_native_heads_ancestry_and_repo_identity_refuse(self):
        cases=[('pulls/99',lambda row:row.update(merged=False)),
               ('pulls/42',lambda row:row['head'].update(sha='e'*40)),
               ('pulls/99',lambda row:row['base']['repo'].update(full_name='foreign/repository')),
               ('compare/'+self.revision+'...main',lambda row:row['merge_base_commit'].update(sha='f'*40))]
        original=copy.deepcopy(self.data)
        for key,change in cases:
            self.data=copy.deepcopy(original);change(self.data[key])
            with self.subTest(key=key),self.assertRaises(collect.Refusal):
                collect.verify_adoption(self.revision,api=self.api)

    def test_modified_collector_evaluator_bytes_or_foreign_import_origin_refuse_before_native_reads(self):
        path=collect.HERE/'campaign_lineage.py';original=path.read_bytes()
        path.write_bytes(original+b'\n# synthetic collector-side drift\n')
        with self.assertRaisesRegex(collect.Refusal,'Collector-side evaluator'):
            collect.verify_adoption(self.revision,api=self.api)
        self.assertEqual(self.calls,[])
        path.write_bytes(original)
        with mock.patch.object(collect.lineage,'__file__',str(self.root/'foreign-lineage.py')):
            with self.assertRaisesRegex(collect.Refusal,'another directory'):
                collect.verify_adoption(self.revision,api=self.api)
        self.assertEqual(self.calls,[])
        self.assertEqual(len(collect.verify_evaluator_custody()),6)


if __name__=='__main__':unittest.main()
