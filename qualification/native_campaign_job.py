"""Native GitHub runner resource gate and evidence witness; never a mutation executor.

The accepted producer owns every mutation and test. The accepted collector owns
positive eligibility. This controller starts only fresh whole partitions 2/3,
does not acknowledge containment, and never retries an aborted invocation.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import pwd
import signal
import stat
import subprocess
import sys
import tarfile
import time

import collect_mutation_evidence as collector

HERE = Path(__file__).absolute().parent
CONTROLLER = HERE.parent
COLLECTOR = '9974c0403a1176406d8cd470745b1555f802ee36'
WORKFLOW = '.github/workflows/qualification-campaign.yml'
BASE = Path('/home/err/.local/share/codex-work/axxium-sso/full-qualification-58234')
PARTITIONS = {
    2: (703, '727d823e658ba8c3877cca63841f3448a33a816334aa34565bb6580e48bfe923'),
    3: (708, 'd31ceea0c7263e3b3a4081333d25e1f36de4334329a820e1512e0c8de7ee6fd1'),
}
INVENTORY = '08ac0fb39ac2ff5a8068891906f867ca14573c50609af99dde2f5c8a97401ce3'
GATE = 'while [[ ! -f "$1" ]]; do sleep 0.1; done; shift; exec "$@"'


def require(value, reason):
    if not value:
        raise ValueError(reason)


def run(argv, *, cwd=CONTROLLER, env=None):
    return subprocess.check_output(argv, cwd=cwd, env=env, text=True).strip()


def write(path, value):
    raw = collector.encoded(value) if not isinstance(value, bytes) else value
    collector.write_new(path, raw)
    return hashlib.sha256(raw).hexdigest()


def context(partition, *, environment=os.environ, api=collector.github, command=run):
    """Authenticate dispatch/run/attempt/job before creating any worker input."""
    require(type(partition) is int and partition in PARTITIONS, 'Only partitions 2 and 3 are admitted')
    require(environment.get('GITHUB_REPOSITORY') == collector.REPOSITORY
            and environment.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and environment.get('GITHUB_REF') == 'refs/heads/main'
            and environment.get('GITHUB_JOB') == 'partition'
            and environment.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Use a native manual main job')
    sha = environment.get('GITHUB_SHA', '')
    require(collector.HEX40.fullmatch(sha) is not None, 'Missing controller source identity')
    require(command(['git', 'rev-parse', 'HEAD']) == sha, 'Checkout differs from the native controller')
    for relative in (WORKFLOW, 'qualification/native_campaign_job.py'):
        path = CONTROLLER/relative
        require(not path.is_symlink() and path.read_bytes() == subprocess.check_output(
            ['git', 'show', sha+':'+relative], cwd=CONTROLLER), 'Native controller source differs')
    run_id, attempt = environment.get('GITHUB_RUN_ID', ''), environment.get('GITHUB_RUN_ATTEMPT', '')
    require(run_id.isdecimal() and int(run_id) > 0 and attempt.isdecimal() and int(attempt) > 0,
            'Missing native run identity')
    native = api('actions/runs/'+run_id+'/attempts/'+attempt)
    require(native.get('id') == int(run_id) and native.get('run_attempt') == int(attempt)
            and native.get('event') == 'workflow_dispatch' and native.get('head_branch') == 'main'
            and native.get('head_sha') == sha and native.get('path') == WORKFLOW
            and native.get('status') == 'in_progress'
            and native.get('repository', {}).get('full_name') == collector.REPOSITORY
            and native.get('head_repository', {}).get('full_name') == collector.REPOSITORY,
            'Native run does not bind this manual main controller')
    require(api('compare/'+sha+'...main').get('merge_base_commit', {}).get('sha') == sha,
            'Controller is not accepted main source')
    jobs = api('actions/runs/'+run_id+'/attempts/'+attempt+'/jobs?per_page=100')
    require(type(jobs.get('total_count')) is int and jobs['total_count'] < 100
            and jobs['total_count'] == len(jobs.get('jobs', [])), 'Native job listing is incomplete')
    matches = [job for job in jobs['jobs'] if job.get('name') == 'Native partition '+str(partition)
               and job.get('runner_name') == environment.get('RUNNER_NAME')
               and job.get('status') == 'in_progress' and job.get('run_id') == int(run_id)
               and job.get('head_sha') == sha and type(job.get('id')) is int and job['id'] > 0]
    require(len(matches) == 1, 'Native job and runner identity are ambiguous')
    return {'repository': collector.REPOSITORY, 'run_id': int(run_id), 'run_attempt': int(attempt),
            'job_id': matches[0]['id'], 'job_name': matches[0]['name'],
            'runner_name': matches[0]['runner_name'], 'controller_sha': sha, 'workflow': WORKFLOW,
            'partition': partition, 'github_hosted': True}


def system_properties(unit, *, command=run):
    raw = command(['sudo', 'systemctl', 'show', unit,
        '--property=LoadState,ActiveState,SubState,MainPID,ControlGroup,MemoryMax,MemoryHigh,MemorySwapMax,CPUQuotaPerSecUSec,TasksMax,KillMode,OOMPolicy,InvocationID,Result,ExecMainStatus'])
    return dict(line.split('=', 1) for line in raw.splitlines())


def active_caps(unit, root, gate, expected_environment, index, *, command=run, proc=Path('/proc'), cgroups=Path('/sys/fs/cgroup')):
    props = system_properties(unit, command=command)
    require(props.get('ActiveState') == 'active' and int(props.get('MainPID', '0')) > 0,
            'Native cap gate is not active')
    expected = {'MemoryMax':str(5*1024**3), 'MemoryHigh':str(4*1024**3), 'MemorySwapMax':'0',
                'CPUQuotaPerSecUSec':'4s', 'TasksMax':'192', 'KillMode':'control-group', 'OOMPolicy':'stop'}
    require(all(props.get(k) == v for k,v in expected.items()), 'Native resource properties differ')
    group = props.get('ControlGroup', '')
    require(group == '/system.slice/'+unit, 'Unexpected native system cgroup')
    native = {name:(cgroups/group.lstrip('/')/name).read_text().strip() for name in
              ('memory.max','memory.high','memory.swap.max','cpu.max','pids.max','cgroup.procs')}
    require(native['memory.max'] == expected['MemoryMax'] and native['memory.high'] == expected['MemoryHigh']
            and native['memory.swap.max'] == '0' and native['pids.max'] == '192', 'Native cgroup caps differ')
    quota, period = map(int, native['cpu.max'].split())
    pid = str(int(props['MainPID']))
    require(quota == 4*period and pid in native['cgroup.procs'].splitlines(), 'Native CPU or PID cap differs')
    require((proc/pid/'cwd').resolve() == root, 'Native gate working directory differs')
    argv = (proc/pid/'cmdline').read_bytes().split(b'\0')
    expected_argv=['/bin/bash','--noprofile','--norc','-c',GATE,'qualification-native-cap-gate',str(gate),*cli(root,index)]
    require(argv == [arg.encode() for arg in expected_argv]+[b''], 'Native gate command differs')
    actual_environment=dict(item.split(b'=',1) for item in (proc/pid/'environ').read_bytes().split(b'\0') if item)
    require(actual_environment == {key.encode():value.encode() for key,value in expected_environment.items()},
            'Native gate environment differs')
    require(props.get('InvocationID'), 'Native unit invocation identity is absent')
    return {'unit':unit, 'properties':props, 'cgroup':native, 'gate_argv':expected_argv,
            'minimal_environment':expected_environment, 'verified_before_execution_ns':time.time_ns()}


def closed(unit, *, witness, command=run, cgroups=Path('/sys/fs/cgroup'), proc=Path('/proc')):
    """Bind origin SYSTEM-bus closure to the previously observed active invocation."""
    require(unit in [unit_name(index) for index in PARTITIONS], 'Unexpected native partition unit')
    require(isinstance(witness,dict) and witness.get('unit')==unit
            and witness.get('properties',{}).get('ActiveState')=='active'
            and witness['properties'].get('InvocationID'), 'Origin active invocation witness is missing')
    props = system_properties(unit, command=command)
    require(props.get('MainPID') == '0' and props.get('ActiveState') in {'inactive','failed'},
            'Native campaign remains active')
    require(props.get('InvocationID') in {'',witness['properties']['InvocationID']},
            'Native unit invocation changed')
    group = props.get('ControlGroup', '')
    require(group in {'', '/system.slice/'+unit}, 'Unexpected native closed cgroup')
    require(not (proc/witness['properties']['MainPID']).exists(), 'Original native controller still exists')
    path = cgroups/'system.slice'/unit
    if path.exists():
        def fail(error):raise error
        for directory,_,_ in os.walk(path,onerror=fail):
            require(not (Path(directory)/'cgroup.procs').read_text().strip(),
                    'Native campaign cgroup descendants are not empty')
    return {'unit':unit, 'native':props, 'bus':'system', 'cgroup_empty_or_absent':True,
            'active_invocation':witness['properties']['InvocationID'], 'original_controller_absent':True,
            'origin_only':True, 'limitation':'Independent acknowledgement is still required after any containment abort.'}


def unit_name(index):
    return 'axxium-qual582-standard-v2-v5-worker'+str(index % 2)+'-batch'+str(index)+'.service'


def cli(root, index):
    return ['/usr/bin/python3','-B','-u','qualification/mutation_campaign.py','--path-prefix','src/cljs/',
        '--profile',collector.PROFILE,'--operator-id','github-native/partition'+str(index),
        '--external-harness-sha',collector.HARNESS,'--producer-revision',collector.PRODUCER,
        '--batch-index',str(index),'--sample-seed',collector.SEED,'--limit','1000','--timeout','300',
        '--out',str(root/'qualification/results/native-standard-v2-v5')]


def progress(root, report, candidates, selection, producer, harness):
    """Monitoring only: rehash completed raw rows and call accepted classifiers/guard."""
    with collector.worker_context(root):
        m, l = collector.campaign, collector.lineage
        reference = l._document(m, report)
        data = reference['document']
        require(l._same(data.get('selection'), selection) and l._same(data.get('producer'), producer)
                and l._same(data.get('test_harness'), harness) and data.get('observed_prefix_start') == 0,
                'Observed native campaign context differs')
        plans, mapping = l._plans(m, candidates, harness)
        if data.get('baseline') is None:
            return {'observed':0, 'eligible':0, 'baseline_pending':True}
        baseline = l._clean_baselines(m, report.parent, data.get('baselines'), plans, prefix='baseline')
        rows = data.get('results')
        require(isinstance(rows, list) and len(rows) <= len(candidates), 'Invalid native observed prefix')
        eligible, reasons = 0, Counter()
        for number,row in enumerate(rows):
            require(all(l._same(row.get(k),v) for k,v in candidates[number].items()), 'Native candidate order differs')
            key = mapping[candidates[number]['fingerprint']]
            require(row.get('test_plan') == key, 'Native static test plan differs')
            raw = l._log(m, report.parent, f'{number:04}.compile.log', row.get('compile_log_sha256'))
            status = m.classify_compile(l._result(row,'compile',raw),len(plans[key]['compile_targets']))
            require(status == row.get('compile_status'), 'Native compile classification differs')
            if status != 'passed':
                require(row.get('phase') == 'compile' and row.get('status') == status, 'Native compiler-only row differs')
                continue
            raw = l._log(m,report.parent,f'{number:04}.test.log',row.get('test_log_sha256'))
            require(m.classify_test(l._result(row,'test',raw),baseline[key]) == row.get('status')
                    and l._same(row.get('driver_summary'),l._safe_driver(m,raw))
                    and l._same(row.get('counts'),m.counts(raw)), 'Native test classification differs')
            if row['status'] == 'killed':
                reason = collector.eligibility_exclusion(row,plans[key],raw)
                if reason is None: eligible += 1
                else: reasons[reason] += 1
        return {'observed':len(rows),'eligible':eligible,'raw':dict(Counter(row['status'] for row in rows)),
                'excluded':dict(reasons),'terminal_state':data.get('terminal_state'),'qualification_credit':False}


def file_sha(path):
    checksum=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):checksum.update(chunk)
    return checksum.hexdigest()


def snapshot(root, destination, origin, *, quiescence_verified=True):
    """Retain every raw result/log and exact reusable input without compiler caches."""
    bundle = destination/'source.bundle'
    subprocess.run(['git','bundle','create',str(bundle),'--all'],cwd=root,check=True,capture_output=True)
    archive = destination/'raw-evidence.tar'
    paths = [root/'qualification/results',root/'qualification/harness',root/'src/cljs']
    paths += [root/'qualification'/name for name in collector.RUNTIME_FILES]
    paths = [path for path in paths if path.exists()]
    manifest = {}
    with tarfile.open(archive,'x') as stream:
        for base in paths:
            for path in [base,*sorted(base.rglob('*'))] if base.is_dir() else [base]:
                relative = path.relative_to(root)
                if any(part in {'target','.shadow-cljs','.cpcache'} for part in relative.parts):continue
                info = path.lstat()
                require(stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode),
                        'Unsupported evidence archive input')
                stream.add(path,arcname=str(relative),recursive=False)
                if stat.S_ISREG(info.st_mode):manifest[str(relative)]={'sha256':file_sha(path),'mode':stat.S_IMODE(info.st_mode)}
                elif stat.S_ISLNK(info.st_mode):manifest[str(relative)]={'symlink':os.readlink(path)}
    write(destination/'artifact-manifest.json',{'schema':'axxium-native-raw-artifact/v1','status':'complete' if quiescence_verified else 'unqualified_snapshot',
        'origin_quiescence_verified':quiescence_verified,'origin':origin,
        'worker_root':str(root),'files':{'source.bundle':file_sha(bundle),
        'raw-evidence.tar':file_sha(archive),
        **{path.name:file_sha(path) for path in destination.iterdir()
           if path.is_file() and path.name not in {'source.bundle','raw-evidence.tar'}}},'raw_members':manifest,
        'node_modules_and_compiler_caches_omitted':True,'production_admitted':False,
        'native_actions_artifact_digest_required':True})


def execute(index, root, unit, destination, origin, adoption, state):
    subprocess.run(['sudo','mkdir','-p',str(BASE)],check=True,capture_output=True)
    subprocess.run(['sudo','chown',str(os.getuid())+':'+str(os.getgid()),str(BASE)],check=True,capture_output=True)
    subprocess.run(['git','clone','--no-hardlinks','--no-checkout',str(CONTROLLER),str(root)],check=True,capture_output=True)
    subprocess.run(['git','checkout','--detach',collector.APPLICATION],cwd=root,check=True,capture_output=True)
    for name in collector.RUNTIME_FILES:
        path=root/'qualification'/name;path.parent.mkdir(mode=0o700,exist_ok=True)
        write(path,subprocess.check_output(['git','show',collector.PRODUCER+':qualification/'+name],cwd=root))
    home=Path.home();node=Path(run(['volta','run','--node','22.23.3','node','-p','process.execPath']))
    require(node.is_absolute() and run([str(node),'--version']) == 'v22.23.3','Pinned native Node is unavailable')
    cache=root/'qualification/fresh-python-cache';cache.mkdir(mode=0o700)
    environment={'HOME':str(home),'USER':pwd.getpwuid(os.getuid()).pw_name,'LOGNAME':pwd.getpwuid(os.getuid()).pw_name,
        'LANG':'C.UTF-8','PATH':str(node.parent)+':'+str(home/'.volta/bin')+':/usr/local/bin:/usr/bin:/bin',
        'VOLTA_HOME':str(home/'.volta'),'JAVA_TOOL_OPTIONS':'-Xmx3g','NODE_OPTIONS':'--max-old-space-size=1024',
        'PYTHONPYCACHEPREFIX':str(cache),'NPM_CONFIG_USERCONFIG':'/dev/null','AXXIUM_TEST_PG_BIN':'/usr/lib/postgresql/16/bin'}
    subprocess.run(['sudo','systemd-run','--wait','--pipe','--uid='+str(os.getuid()),'--gid='+str(os.getgid()),
        '--property=WorkingDirectory='+str(root),'--property=MemoryMax=2G','--property=MemorySwapMax=0',
        '--property=CPUQuota=200%','--property=TasksMax=96','--property=RuntimeMaxSec=600',
        '/usr/bin/env','-i',*[k+'='+v for k,v in environment.items()],str(node.parent/'npm'),'ci','--ignore-scripts','--no-audit','--no-fund'],check=True)
    with collector.worker_context(root):
        m,l,h=collector.campaign,collector.lineage,collector.external_harness
        m.require_unchanged_tree();m.prepare_evidence_directories()
        harness=h.prepare(root,collector.APPLICATION,collector.HARNESS);h.validate(root,harness)
        producer=m.producer_custody(collector.PRODUCER,external=True)
        candidates,selection=l.select_candidates(m,profile=collector.PROFILE,path_prefix='src/cljs/',batch_index=index,sample_seed=collector.SEED)
        require(len(candidates)==PARTITIONS[index][0] and selection['selection_sha256']==PARTITIONS[index][1]
                and selection['inventory_sha256']==INVENTORY and len(selection['inventory_fingerprints'])==2852,
                'Native whole selection differs')
        require(len(harness['files'])==130 and harness['test_support_paths']==sorted(h.TEST_SUPPORT_PATHS),'Native harness differs')
    gate=root/'qualification/results/.native-launch-ready';report=root/'qualification/results/native-standard-v2-v5/report.json'
    argv=['sudo','systemd-run','--unit='+unit,'--service-type=exec','--uid='+str(os.getuid()),'--gid='+str(os.getgid()),
        '--property=WorkingDirectory='+str(root),'--property=MemoryMax=5G','--property=MemoryHigh=4G',
        '--property=MemorySwapMax=0','--property=CPUQuota=400%','--property=TasksMax=192',
        '--property=KillMode=control-group','--property=OOMPolicy=stop','--property=RuntimeMaxSec=19800',
        '/usr/bin/env','-i',*[k+'='+v for k,v in environment.items()],'/bin/bash','--noprofile','--norc','-c',GATE,
        'qualification-native-cap-gate',str(gate),*cli(root,index)]
    subprocess.run(argv,check=True,capture_output=True)
    caps=active_caps(unit,root,gate,environment,index)
    state['caps']=caps
    write(root/'qualification/results/native-launch.json',{'origin':origin,'adoption':adoption,'caps':caps,
        'command':argv,'selection':selection,'producer':producer,'harness':harness,'qualification_credit':False})
    write(gate,b'ACTIVE native caps and immutable inputs verified before canonical execution.\n')
    start=time.monotonic();signalled=False;summary=None;stop_reason=None
    while system_properties(unit)['ActiveState']=='active':
        if report.exists():
            summary=progress(root,report,candidates,selection,producer,harness)
            print(json.dumps(summary),flush=True)
        free=os.statvfs(root)
        raw_size=sum(path.stat().st_size for path in (root/'qualification/results').rglob('*') if path.is_file())
        low_disk=free.f_bavail*free.f_frsize < max(2*1024**3,raw_size+1024**3)
        if not signalled and ((summary or {}).get('eligible',0)>=250 or time.monotonic()-start>18000 or low_disk):
            props=system_properties(unit);pid=int(props['MainPID'])
            require(pid>0 and Path('/proc/'+str(pid)+'/cwd').resolve()==root,'Native stop controller differs')
            actual=Path('/proc/'+str(pid)+'/cmdline').read_bytes().split(b'\0')
            require(actual==[part.encode() for part in cli(root,index)]+[b''],'Native stop command differs')
            os.kill(pid,signal.SIGUSR1);signalled=True
            stop_reason='250-strict-positive-observations' if (summary or {}).get('eligible',0)>=250 else 'resource-or-time-bound'
        time.sleep(20)
    closure=closed(unit,witness=state['caps'])
    if report.exists():summary=progress(root,report,candidates,selection,producer,harness)
    write(root/'qualification/results/native-terminal.json',{'origin':origin,'closure':closure,'progress':summary,
        'stop_reason':stop_reason,'self_acknowledged':False,'retry_attempted':False,'qualification_credit':False})
    collected=False
    if summary and summary.get('terminal_state') in {'clean_partial','complete'} and summary.get('eligible',0)>=250:
        entry={'batch_index':index,'worker_root':str(root),'report':str(report),'unit':unit}
        with collector.read_leases([root]):
            batch,audit=collector.collect_batch(entry,observe_unit=lambda name:closed(name,witness=state['caps']))
        require(collector.verify_adoption(COLLECTOR)==adoption,'Collector native adoption changed')
        write(destination/'partition.json',{'origin':origin,'adoption':adoption,'batch':batch,'audit':audit,
            'production_admitted':False,'four_partition_aggregation_and_native_artifact_custody_required':True})
        collected=True
    require(collected,'Native partition retained without qualification; independent investigation may be required')


def preserve(root, destination, origin, unit, state):
    """Observation failure cannot suppress the independently attempted raw snapshot."""
    errors=[];verified=False
    try:
        props=system_properties(unit)
        forced=props.get('ActiveState')=='active'
        if forced:
            subprocess.run(['sudo','systemctl','stop',unit],check=True,capture_output=True,timeout=120)
        closure=(closed(unit,witness=state['caps']) if 'caps' in state else
                 {'no_active_invocation_witness':True,'native':system_properties(unit),'qualification_credit':False})
        verified='caps' in state
        write(destination/'origin-closure.json',{'origin':origin,'closure':closure,
            'forced_stop':forced,'restoration_or_acknowledgement_claimed':False})
    except Exception as error:
        errors.append('origin-closure')
        write(destination/'origin-closure-refusal.json',{'stage':'origin-closure','error_type':type(error).__name__,
            'closure_verified':False,'production_admitted':False,'self_acknowledged':False})
    try:
        with (destination/'native-unit.log').open('xb') as output:
            subprocess.run(['sudo','journalctl','--unit='+unit,'--no-pager','--output=cat'],check=True,stdout=output)
    except Exception as error:
        errors.append('native-journal')
        write(destination/'journal-refusal.json',{'stage':'native-journal','error_type':type(error).__name__})
    try:
        if (root/'.git').exists():
            if (root/'qualification/results/.campaign.lock').exists():
                leased=False
                try:
                    with collector.read_leases([root]):
                        leased=True
                        snapshot(root,destination,origin,quiescence_verified=verified)
                except collector.Refusal:
                    if leased:raise
                    # A still-held lock allows only explicitly unqualified raw
                    # capture; it supplies neither a coherent history nor credit.
                    errors.append('active-campaign-lock')
                    snapshot(root,destination,origin,quiescence_verified=False)
            else:snapshot(root,destination,origin,quiescence_verified=False)
    except Exception as error:
        errors.append('raw-snapshot')
        write(destination/'snapshot-refusal.json',{'stage':'raw-snapshot','error_type':type(error).__name__})
    require(not errors,'Native evidence preservation has unqualified stages: '+','.join(errors))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--partition',type=int,required=True)
    args=parser.parse_args();origin=context(args.partition)
    adoption=collector.verify_adoption(COLLECTOR)
    index=args.partition;root=BASE/('worker'+str(index));unit=unit_name(index)
    destination=CONTROLLER/('native-evidence-'+str(index))
    require(not root.exists() and not destination.exists(),'Use a fresh ephemeral worker and evidence directory')
    destination.mkdir(mode=0o700)
    write(destination/'origin.json',{'origin':origin,'adoption':adoption,'production_admitted':False})
    state={}
    try:
        execute(index,root,unit,destination,origin,adoption,state)
    except Exception as error:
        write(destination/'controller-refusal.json',{'error_type':type(error).__name__,
            'production_admitted':False,'self_acknowledged':False,'retry_attempted':False})
        raise
    finally:
        preserve(root,destination,origin,unit,state)


if __name__=='__main__':
    try:main()
    except Exception as error:
        print(json.dumps({'native_campaign_complete':False,'error_type':type(error).__name__,
                          'production_admitted':False}),flush=True)
        raise SystemExit(1) from None
