"""Collect verified positive evidence; this read-only tool grants no deployment admission."""
import argparse
from collections import Counter
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess

import campaign_lineage as lineage
import external_harness
import mutation_campaign as campaign
import test_phase

REPOSITORY = 'open-hax/axxium'
APPLICATION = '58234df96ca86507e6cdd87f65aaa9ab35621415'
PRODUCER = 'b87a7ddf24d63147f2a8c85a789fb97b55047c9e'
HARNESS = '9bfa2725a1763380d3bbb55b884bbeed060aa04c'
PRODUCER_PR = 42
PRODUCER_MERGE = '0c26a22a1a1a7ede0aaf7398c8480c43077c114f'
PROFILE = 'standard-v2'
SEED = 'full-qualification-58234-v1'
REQUIRED_KILLS = 250
HEX40 = re.compile(r'[0-9a-f]{40}')
UNIT = re.compile(r'axxium-qual582-standard-v2-v5-worker[01](?:-batch[23])?(?:-continue[1-9][0-9]{0,2})?\.service')
RUNTIME_FILES = ('mutation_campaign.py', 'campaign_lineage.py', 'external_harness.py',
                 'test_phase.py', 'assertion_measurement.mjs', 'node_test_reporter.mjs')
AUTHORITY_DONE = 'QUALIFICATION_CHILD[protocol] PASS durable authority:'
PROTOCOL_FRAGMENT = 'QUALIFICATION_CHILD[protocol] '
AUTHORITY_PAYLOAD = 'PASS durable authority:'
HERE = Path(__file__).resolve().parent


class Refusal(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False) + '\n').encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def command(argv, *, cwd=None):
    return subprocess.check_output(argv, cwd=cwd, text=True).strip()


def github(endpoint):
    return json.loads(command(['gh', 'api', 'repos/' + REPOSITORY + '/' + endpoint]))


def accepted_pr(value, head):
    return (isinstance(value, dict) and value.get('merged') is True
            and type(value.get('number')) is int and value['number'] > 0
            and value.get('base', {}).get('ref') == 'main'
            and value.get('base', {}).get('repo', {}).get('full_name') == REPOSITORY
            and value.get('head', {}).get('repo', {}).get('full_name') == REPOSITORY
            and value.get('head', {}).get('sha') == head
            and isinstance(value.get('merge_commit_sha'), str)
            and HEX40.fullmatch(value['merge_commit_sha']) is not None)


def verify_evaluator_custody():
    """Bind actual collector-side evaluator files independently of their methods."""
    origins = {'mutation_campaign.py': campaign, 'campaign_lineage.py': lineage,
               'external_harness.py': external_harness, 'test_phase.py': test_phase}
    hashes = {}
    for name in RUNTIME_FILES:
        path = HERE/name
        info = path.lstat()
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                'A collector evaluator file is redirected or not regular')
        if name in origins:
            require(Path(origins[name].__file__).absolute() == path,
                    'An imported evaluator came from another directory')
        raw = path.read_bytes()
        expected = subprocess.check_output(['git','show',PRODUCER+':qualification/'+name],cwd=HERE.parent)
        require(raw == expected, 'Collector-side evaluator differs from the immutable producer')
        hashes[name] = sha(raw)
    return hashes


def verify_adoption(collector_revision, *, api=github):
    """Git equality, native accepted PR and main ancestry are distinct checks."""
    require(isinstance(collector_revision, str) and HEX40.fullmatch(collector_revision),
            'An immutable collector revision is required')
    evaluators = verify_evaluator_custody()
    raw = Path(__file__).read_bytes()
    require(subprocess.check_output(['git', 'show', collector_revision + ':qualification/' + Path(__file__).name],
                                    cwd=HERE.parent) == raw, 'Collector differs from its Git revision')
    producer = api('pulls/' + str(PRODUCER_PR))
    require(accepted_pr(producer, PRODUCER) and producer['merge_commit_sha'] == PRODUCER_MERGE,
            'Producer native adoption differs')
    require(api('compare/' + PRODUCER + '...main').get('merge_base_commit', {}).get('sha') == PRODUCER,
            'Producer is not a native main ancestor')
    require(api('compare/' + HARNESS + '...' + PRODUCER).get('merge_base_commit', {}).get('sha') == HARNESS,
            'Pure harness is not an adopted producer ancestor')
    pulls = api('commits/' + collector_revision + '/pulls?per_page=100')
    require(isinstance(pulls, list) and len(pulls) < 100, 'Collector adoption is unavailable or incomplete')
    candidates = [pr for pr in pulls if isinstance(pr, dict)
                  and type(pr.get('number')) is int and pr['number'] > 0
                  and pr.get('head', {}).get('sha') == collector_revision]
    accepted = [native for pr in candidates
                if accepted_pr(native := api('pulls/' + str(pr['number'])), collector_revision)]
    require(len(accepted) == 1, 'Collector needs one exact-head native merged-main PR')
    require(api('compare/' + collector_revision + '...main').get('merge_base_commit', {}).get('sha') == collector_revision,
            'Collector is not a native main ancestor')
    return {'collector_revision': collector_revision, 'collector_sha256': sha(raw),
            'evaluator_runtime_files': evaluators,
            'collector_pr': accepted[0]['number'], 'collector_merge_sha': accepted[0]['merge_commit_sha'],
            'producer_pr': PRODUCER_PR, 'producer_revision': PRODUCER, 'producer_merge_sha': PRODUCER_MERGE,
            'pure_harness_sha': HARNESS, 'native_adoption_verified': True}


def absolute(path):
    value = Path(path)
    require(value.is_absolute() and '..' not in value.parts, 'Input paths must be absolute and bounded')
    for parent in [value, *value.parents]:
        require(not parent.is_symlink(), 'Input paths must retain no redirected parents')
    return value


def unit_closed(unit, *, execute=None):
    require(isinstance(unit, str) and UNIT.fullmatch(unit), 'Unexpected native campaign unit')
    argv = ['systemctl', '--user', 'show', unit,
            '--property=LoadState,ActiveState,SubState,MainPID,ControlGroup,InvocationID,Result']
    if execute is None:
        observed = subprocess.run(argv, capture_output=True, text=True, check=False)
        require(observed.returncode in {0, 1}, 'Native unit observation is unavailable')
        raw = observed.stdout
    else:
        raw = execute(argv)
    value = dict(line.split('=', 1) for line in raw.splitlines())
    require(value.get('MainPID') == '0' and value.get('ActiveState') in {'inactive', 'failed'},
            'A campaign unit is still active')
    group = value.get('ControlGroup')
    if group:
        require(group.startswith('/user.slice/') and '..' not in Path(group).parts,
                'Native cgroup path is unexpected')
        path = Path('/sys/fs/cgroup') / group.lstrip('/') / 'cgroup.procs'
        require(not path.exists() or not path.read_text().strip(), 'Native campaign cgroup is not empty')
    return {'unit': unit, 'native': value, 'cgroup_empty_or_absent': True,
            'limitation': 'Local native observation; escaped processes and inspector identity are not authenticated by JSON.'}


@contextmanager
def read_leases(roots):
    """Reuse the canonical lock without creating or changing a worker file."""
    descriptors = []
    try:
        for root in sorted(set(roots)):
            fd = os.open(root/'qualification/results/.campaign.lock', os.O_RDONLY | os.O_NOFOLLOW)
            descriptors.append(fd)
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid(),
                    'A worker campaign lock is not an owned regular file')
            try:
                fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Refusal('A canonical campaign still owns a worker') from None
        yield
    finally:
        for fd in descriptors:
            os.close(fd)


@contextmanager
def worker_context(root):
    saved = campaign.ROOT, campaign.SOURCE, campaign.SOURCE_SHA
    campaign.ROOT, campaign.SOURCE, campaign.SOURCE_SHA = root, root / 'src/cljs', APPLICATION
    try:
        yield
    finally:
        campaign.ROOT, campaign.SOURCE, campaign.SOURCE_SHA = saved


def eligibility_exclusion(row, plan, output=None):
    """Legacy-text fallback cannot supply external-harness positive evidence."""
    if row.get('status') != 'killed' or row.get('phase') != 'test':
        return 'not-a-raw-test-kill'
    driver = row.get('driver_summary')
    if not isinstance(driver, dict):
        return 'missing-structured-driver-summary'
    try:
        campaign.driver_summary('QUALIFICATION_TEST_SUMMARY=' + json.dumps(driver))
    except (TypeError, ValueError):
        return 'invalid-structured-driver-summary'
    strict = (driver.get('schema') == 'axxium-qualification-test-phase/v1'
            and driver.get('plan') == plan['key']
            and [group.get('name') for group in driver.get('groups', [])] == plan['groups']
            and type(driver.get('failures')) is int and driver['failures'] > 0
            and driver.get('errors') == 0 and driver.get('warnings') == []
            and driver.get('timed_out') is False and driver.get('containment_unknown') is False
            and row.get('compile_status') == 'passed' and type(row.get('compile_exit')) is int and row['compile_exit'] == 0
            and type(row.get('test_exit')) is int and row['test_exit'] == 1
            and all(row.get(prefix + '_' + flag) is False for prefix in ('compile', 'test')
                    for flag in ('timed_out', 'output_overflow', 'containment_unknown')))
    if not strict:
        return 'structured-driver-or-execution-boundary-not-clean'
    protocol = next((group for group in driver['groups'] if group['name'] == 'protocol'), None)
    if protocol is not None and protocol['failures'] > 0:
        if not isinstance(output, str):
            return 'protocol-raw-output-unavailable'
        # The immutable child driver prefixes each read fragment, not complete
        # logical lines. Removing those prefixes and joining every payload can
        # over-exclude ambiguous output; it cannot miss an ASCII completion
        # marker split at the producer's 8192-byte read boundary.
        payload = ''.join(line[len(PROTOCOL_FRAGMENT):] for line in output.splitlines()
                          if line.startswith(PROTOCOL_FRAGMENT))
        if AUTHORITY_PAYLOAD in payload:
            return 'protocol-failure-after-authority-observer-scope'
    return None


def eligible(row, plan, output=None):
    return eligibility_exclusion(row, plan, output) is None


def observed_corpus(history):
    """Use canonical read/hash checks again; never silently replace an ancestor."""
    documents = []
    for reference in history['report_refs']:
        current = lineage._document(campaign, reference['path'])
        require(current['sha256'] == reference['sha256'], 'An ancestor changed after validation')
        documents.append(current)
    documents.append(history)
    observations = []
    for reference in documents:
        data = reference['document']
        for index, row in enumerate(data['results']):
            observations.append({'ordinal': data['observed_prefix_start'] + index,
                'report': {'path': reference['path'], 'sha256': reference['sha256']},
                'result_index': index, 'outcome': row})
    require([item['ordinal'] for item in observations] == list(range(history['next_index'])),
            'The cumulative raw corpus is not one complete prefix')
    return documents, observations


def collect_batch(entry, *, observe_unit=unit_closed):
    verify_evaluator_custody()
    require(isinstance(entry, dict), 'A batch input must be an object')
    index = entry.get('batch_index')
    require(type(index) is int and index in range(4), 'Batch index is invalid')
    require(all(isinstance(entry.get(key), str) and entry[key] for key in ('worker_root','unit','report')),
            'A batch requires worker_root, unit and report inputs')
    root = absolute(entry['worker_root'])
    require(root.is_dir() and root.stat().st_uid == os.getuid(), 'Worker ownership is unavailable')
    closure = observe_unit(entry['unit'])
    with worker_context(root):
        campaign.require_unchanged_tree()
        campaign.require_no_containment_marker()
        require(not list((root/'qualification/results').rglob('inflight.json')), 'An inflight source mutation remains')
        producer = campaign.producer_custody(PRODUCER, external=True)
        for name in RUNTIME_FILES:
            path = root/'qualification'/name
            require(not path.is_symlink() and path.read_bytes() == campaign.git('show', PRODUCER + ':qualification/' + name),
                    'A worker runtime differs from its adopted producer')
        descriptor_path = root/'qualification/harness'/HARNESS/external_harness.DESCRIPTOR
        require(not descriptor_path.is_symlink(), 'The harness descriptor is redirected')
        harness = json.loads(descriptor_path.read_text())
        external_harness.validate(root, harness)
        require(harness['harness_sha'] == HARNESS, 'The external harness differs')
        fixture_bytes = campaign.git('show', HARNESS + ':scripts/oidc-protocol.fixture.mjs')
        require(fixture_bytes.count(b"console.log('PASS durable authority:") == 1,
                'The immutable authority scope marker differs')
        guard = {'schema': 'axxium-positive-evidence-eligibility/v1', 'harness_sha': HARNESS,
                 'fixture_path': 'scripts/oidc-protocol.fixture.mjs', 'fixture_sha256': sha(fixture_bytes),
                 'producer_revision': PRODUCER, 'driver_sha256': producer['files']['test_phase.py'],
                 'authority_done_raw_prefix': AUTHORITY_DONE,
                 'protocol_fragment_raw_prefix': PROTOCOL_FRAGMENT, 'authority_payload_marker': AUTHORITY_PAYLOAD,
                 'framing': 'Conservatively concatenate all protocol-prefixed payload fragments before marker search.',
                 'exclusion': 'Protocol failures after authority completed have no complete query-error observer; exclude the entire row, including mixed group failures.'}
        candidates, selection = lineage.select_candidates(campaign, profile=PROFILE,
            path_prefix='src/cljs/', batch_index=index, sample_seed=SEED)
        history = lineage.load_history(campaign, absolute(entry['report']), candidates=candidates,
            selection=selection, producer=producer, harness=harness)
        terminal = history['document']
        closure_refs = {}
        if terminal['terminal_state'] == 'aborted':
            require(all(isinstance(entry.get(key), str) and entry[key] for key in ('containment_ack','restoration')),
                    'An aborted report requires containment_ack and restoration inputs')
            ack = lineage.validate_ack(campaign, absolute(entry['containment_ack']), history)
            restored = lineage.validate_restoration(campaign, absolute(entry['restoration']), history=history,
                ack=ack, candidates=candidates, selection=selection, producer=producer, harness=harness)
            closure_refs = {name: {key: value[key] for key in ('path','sha256')}
                            for name, value in [('containment_ack', ack), ('restoration', restored)]}
        else:
            require('containment_ack' not in entry and 'restoration' not in entry,
                    'Unrelated terminal containment evidence is not accepted')
            require(terminal.get('restored_baseline') == 'passed', 'The terminal plans were not restored')
        documents, observations = observed_corpus(history)
        positives, excluded_claims = [], []
        for item in observations:
            row = item['outcome']
            if row['status'] != 'killed':
                continue
            output = lineage._log(campaign, Path(item['report']['path']).parent,
                f"{item['result_index']:04}.test.log", row['test_log_sha256'])
            reason = eligibility_exclusion(row, terminal['test_plans'][row['test_plan']], output)
            if reason is None:
                positives.append(item)
            else:
                excluded_claims.append({'ordinal': item['ordinal'], 'fingerprint': row['fingerprint'], 'reason': reason})
        require(len(positives) >= REQUIRED_KILLS,
                'Batch ' + str(index) + ' has only ' + str(len(positives)) + ' eligible actual test kills')
        fingerprints = [item['outcome']['fingerprint'] for item in observations]
        require(len(fingerprints) == len(set(fingerprints)), 'A cumulative source mutation is duplicated')
        picked = positives[:REQUIRED_KILLS]
        selected = []
        for item in picked:
            row = item['outcome']
            selected.append({'fingerprint': row['fingerprint'], 'phase': 'test', 'status': 'killed',
                'warnings': [], 'timed_out': False, 'evidence': {
                    'ordinal': item['ordinal'], 'report_sha256': item['report']['sha256'],
                    'result_index': item['result_index'], 'test_plan': row['test_plan'],
                    'compile_log_sha256': row['compile_log_sha256'], 'test_log_sha256': row['test_log_sha256']}})
        audit = {'batch_index': index, 'selection': selection, 'producer': producer, 'test_harness': harness,
            'reports': [{key: doc[key] for key in ('path','sha256')} for doc in documents],
            'terminal_closure': closure_refs, 'native_unit_closure': closure,
            'raw_outcome_counts': dict(sorted(Counter(item['outcome']['status'] for item in observations).items())),
            'observed_prefix_end': history['next_index'], 'observations': observations,
            'unexecuted_fingerprints': terminal['unexecuted_fingerprints'],
            'eligible_actual_kills': len(positives), 'excluded_raw_kill_claims': excluded_claims,
            'eligibility_guard': guard,
            'selected_ordinals': [item['ordinal'] for item in picked], 'score_claim': None,
            'meaning': 'First 250 verified positive outcomes; the complete raw corpus is not relabelled.'}
        batch = {'source_sha': APPLICATION, 'batch_index': index, 'status': 'passed', 'baseline': 'passed',
            'warnings': [], 'timed_out': False, 'mutants': selected,
            'collection_law': 'first-250-strict-actual-test-kills-in-frozen-cumulative-order/v1',
            'raw_outcome_counts': audit['raw_outcome_counts'], 'observed_prefix_end': history['next_index'],
            'eligible_actual_kills': len(positives), 'excluded_raw_kill_claim_count': len(excluded_claims),
            'eligibility_exclusion_counts': dict(sorted(Counter(item['reason'] for item in excluded_claims).items())),
            'eligibility_guard': guard,
            'unexecuted_count': len(terminal['unexecuted_fingerprints']),
            'selection_sha256': selection['selection_sha256'], 'inventory_sha256': selection['inventory_sha256'],
            'score_claim': None}
        campaign.require_unchanged_tree()
        campaign.require_no_containment_marker()
        external_harness.validate(root, harness)
        return batch, audit


def collect(entries, *, observe_unit=unit_closed):
    verify_evaluator_custody()
    require(isinstance(entries, list) and len(entries) == 4, 'Exactly four partitions are required')
    require(all(isinstance(row, dict) for row in entries), 'Malformed partition input')
    require({row.get('batch_index') for row in entries} == set(range(4))
            and all(type(row.get('batch_index')) is int for row in entries), 'Indexes must be exactly 0 through 3')
    require(all(isinstance(row.get('worker_root'), str) and row['worker_root'] for row in entries),
            'Each batch requires a worker_root input')
    batches, audits, seen = [], [], set()
    with read_leases([absolute(row['worker_root']) for row in entries]):
        for entry in sorted(entries, key=lambda value: value['batch_index']):
            batch, audit = collect_batch(entry, observe_unit=observe_unit)
            for mutant in batch['mutants']:
                require(mutant['fingerprint'] not in seen, 'Selected mutations duplicate another partition')
                seen.add(mutant['fingerprint'])
            batches.append(batch); audits.append(audit)
    require(len(seen) == 4*REQUIRED_KILLS, 'The positive collection size differs')
    return batches, audits


def write_new(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--collector-revision', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    stage = 'input-plan'
    try:
        inputs = absolute(args.inputs)
        require(inputs.is_file() and inputs.stat().st_size <= 65536, 'Collection input plan is unavailable')
        plan = json.loads(inputs.read_text(), object_pairs_hook=lineage._pairs)
        require(isinstance(plan, dict) and plan.get('schema') == 'axxium-collection-inputs/v1', 'Unknown input plan')
        stage = 'native-adoption'
        adoption = verify_adoption(args.collector_revision)
        stage = 'raw-evidence-collection'
        batches, audits = collect(plan.get('batches'))
        stage = 'native-adoption-recheck'
        require(verify_adoption(args.collector_revision) == adoption, 'Native source adoption changed during collection')
        stage = 'output-bundle'
        audit = {'schema': 'axxium-mutation-collection-audit/v1', 'repository': REPOSITORY,
                 'source_sha': APPLICATION, 'adoption': adoption, 'partitions': audits,
                 'production_admitted': False, 'raw_outcomes_preserved': True}
        audit_bytes = encoded(audit)
        result = {'schema': 'axxium-positive-mutation-collections/v1', 'repository': REPOSITORY,
                  'source_sha': APPLICATION, 'mutation_batches': batches,
                  'audit': {'name': 'audit.json', 'sha256': sha(audit_bytes)},
                  'production_admitted': False, 'publication_and_services_admission_required': True}
        result_bytes = encoded(result)
        require(len(result_bytes) < 768*1024, 'Compact collections exceed the Services evidence budget')
        destination = absolute(args.out)
        require(not destination.exists() and destination.parent.is_dir()
                and destination.parent.stat().st_uid == os.getuid(), 'Use a fresh output in an owned existing parent')
        roots = [absolute(row['worker_root']) for row in plan['batches']]
        require(not any(destination.is_relative_to(root) for root in roots), 'Collection must not modify worker directories')
        destination.mkdir(mode=0o700)
        write_new(destination/'audit.json', audit_bytes)
        write_new(destination/'mutation-batches.json', result_bytes)
        manifest = {'schema': 'axxium-mutation-collection-manifest/v1', 'status': 'complete',
                    'source_sha': APPLICATION, 'adoption': adoption, 'production_admitted': False,
                    'files': {'audit.json': sha(audit_bytes), 'mutation-batches.json': sha(result_bytes)}}
        write_new(destination/'manifest.json', encoded(manifest))
        descriptor = os.open(destination, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
        print(json.dumps({'collected': True, 'selected_actual_test_kills': len(batches)*REQUIRED_KILLS,
                          'output': str(destination), 'manifest_sha256': sha(encoded(manifest)),
                          'production_admitted': False}))
    except Refusal as error:
        print(json.dumps({'collected': False, 'reason': str(error), 'production_admitted': False}))
        raise SystemExit(1) from None
    except Exception as error:
        print(json.dumps({'collected': False, 'reason': 'Native or source evidence unavailable or inconsistent',
                          'stage': stage, 'error_type': type(error).__name__,
                          'production_admitted': False}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
