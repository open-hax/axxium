"""Strict read-only custody checks for canonical mutation continuation.

Inspector names are declarations, not authentication. A distinct declared name
must be supported by operationally independent inspection and native task/ledger
evidence before a caller treats an acknowledgement as independently inspected.
No artifact-supplied command is executed by this module.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat

MAX_DOCUMENT = 32 * 1024 * 1024
MAX_LOG = 8 * 1024 * 1024
MAX_ANCESTORS = 32
PROFILES = {'legacy-v1', 'standard-v2'}
HEX = re.compile(r'[0-9a-f]{64}')


def _hash(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def _same(left, right):
    """JSON booleans must not compare equal to integer counters or offsets."""
    return _canonical(left) == _canonical(right)


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _integer(value, minimum=0):
    return type(value) is int and value >= minimum


def _text(value, minimum=1, maximum=1024):
    return isinstance(value, str) and minimum <= len(value.strip()) <= maximum and '\x00' not in value


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def _read(campaign, path, maximum):
    """Reject traversal, links, alien ownership and concurrent replacement."""
    base = Path(campaign.ROOT).absolute() / 'qualification/results'
    _require(isinstance(path, (str, Path)), 'Missing evidence path')
    path = Path(path)
    _require(path.is_absolute() and '..' not in path.parts, 'Evidence path must be absolute and bounded')
    _require(path.is_relative_to(base) and path != base, 'Evidence path outside canonical results')
    current = Path(campaign.ROOT).absolute()
    for part in path.relative_to(current).parts:
        current /= part
        info = current.lstat()
        _require(not stat.S_ISLNK(info.st_mode) and info.st_uid == os.getuid(), 'Evidence path must be owned without links')
        if current == base or current.is_relative_to(base):
            _require(not info.st_mode & 0o022, 'Evidence must not be externally writable')
        if current != path:
            _require(stat.S_ISDIR(info.st_mode), 'Evidence parent must be a directory')
    _require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= maximum,
             'Evidence must be a bounded regular unlinked file')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        _require((opened.st_dev, opened.st_ino) == (info.st_dev, info.st_ino), 'Evidence changed before reading')
        chunks, size = [], 0
        while True:
            chunk = os.read(fd, min(65536, maximum + 1 - size))
            if not chunk:
                break
            chunks.append(chunk); size += len(chunk)
            _require(size <= maximum, 'Evidence exceeds read limit')
        after = os.fstat(fd)
        _require((opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) ==
                 (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'Evidence changed while reading')
    finally:
        os.close(fd)
    final = path.lstat()
    _require((final.st_dev, final.st_ino) == (opened.st_dev, opened.st_ino), 'Evidence path changed while reading')
    return b''.join(chunks)


def _document(campaign, path):
    raw = _read(campaign, path, MAX_DOCUMENT)
    data = json.loads(raw.decode('utf-8'), object_pairs_hook=_pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite JSON number')))
    _require(isinstance(data, dict), 'Expected a JSON object')
    return {'path': str(Path(path)), 'sha256': _hash(raw), 'document': data}


def _log(campaign, directory, name, expected):
    _require(isinstance(expected, str) and HEX.fullmatch(expected), 'Malformed log digest')
    raw = _read(campaign, directory / name, MAX_LOG)
    _require(_hash(raw) == expected, 'Raw execution log differs from its recorded hash')
    return raw.decode('utf-8')


def select_candidates(campaign, *, profile, path_prefix, batch_index, sample_seed):
    """Freeze the entire inventory and ordered partition before any execution."""
    _require(profile in PROFILES, 'Unknown operator profile')
    _require(isinstance(path_prefix, str) and path_prefix.startswith('src/cljs/')
             and '..' not in Path(path_prefix).parts, 'Invalid source prefix')
    _require(batch_index is None or type(batch_index) is int and 0 <= batch_index <= 3, 'Invalid partition')
    _require(sample_seed is None or _text(sample_seed), 'Invalid ordering seed')
    inventory = campaign.inventory(profile=profile)
    fingerprints = [row.get('fingerprint') for row in inventory]
    _require(all(isinstance(fp, str) and HEX.fullmatch(fp) for fp in fingerprints)
             and len(fingerprints) == len(set(fingerprints)), 'Invalid inventory fingerprints')
    selected = [row for row in inventory if row['path'].startswith(path_prefix)
                and (batch_index is None or campaign.batch_for(row['fingerprint']) == batch_index)]
    if sample_seed is not None:
        selected.sort(key=lambda row: _hash((sample_seed + ':' + row['fingerprint']).encode()))
    _require(bool(selected), 'No candidates selected')
    all_fingerprints = sorted(fingerprints)
    ordered = [row['fingerprint'] for row in selected]
    selection = {'schema': 'axxium-mutation-selection/v1', 'source_sha': campaign.SOURCE_SHA,
                 'profile': profile, 'path_prefix': path_prefix, 'batch_index': batch_index,
                 'sample_seed': sample_seed, 'inventory_fingerprints': all_fingerprints,
                 'ordered_fingerprints': ordered, 'inventory_sha256': _hash(_canonical(all_fingerprints)),
                 'selection_sha256': _hash(_canonical(ordered))}
    return selected, selection


def _plans(campaign, candidates, harness):
    plans, mapping = {}, {}
    for candidate in candidates:
        plan = campaign.static_plan_for_path(candidate['path']) if harness is not None else {
            'key': 'cljs', 'compile_targets': ['test'], 'groups': ['cljs']}
        key = plan.get('key')
        _require(isinstance(key, str) and re.fullmatch(r'[a-z0-9+-]+', key), 'Invalid static plan key')
        _require(isinstance(plan.get('compile_targets'), list) and plan['compile_targets']
                 and isinstance(plan.get('groups'), list) and plan['groups'], 'Invalid static plan')
        _require(key not in plans or plans[key] == plan, 'Conflicting static plans')
        plans[key] = plan
        mapping[candidate['fingerprint']] = key
    return plans, mapping


def _safe_driver(campaign, output):
    try:
        return campaign.driver_summary(output)
    except (ValueError, TypeError):
        return None


def _result(row, prefix, output):
    exit_code = row.get(prefix + '_exit')
    _require(exit_code is None or type(exit_code) is int, 'Invalid execution exit')
    values = {}
    for suffix in ('timed_out', 'output_overflow', 'containment_unknown'):
        value = row.get(prefix + '_' + suffix)
        _require(type(value) is bool, 'Missing execution boundary flag')
        values[suffix] = value
    _require(not (values['timed_out'] or values['output_overflow']) or values['containment_unknown'],
             'Aborted execution falsely claims containment')
    _require(exit_code is not None or values['containment_unknown'], 'Incomplete execution has no containment block')
    return {'exit_code': exit_code, 'output': output, **values}


def _clean_baselines(campaign, directory, rows, plans, *, prefix, previous=None):
    _require(isinstance(rows, dict) and set(rows) == set(plans), 'Missing or extra baseline plan')
    checked = {}
    for key, plan in plans.items():
        row = rows[key]
        _require(isinstance(row, dict), 'Malformed baseline')
        compile_output = _log(campaign, directory, f'{prefix}.{key}.compile.log', row.get('compile_log_sha256'))
        test_output = _log(campaign, directory, f'{prefix}.{key}.test.log', row.get('test_log_sha256'))
        clean_execution = {'exit_code': 0, 'timed_out': False, 'containment_unknown': False, 'output_overflow': False}
        _require(campaign.classify_compile({**clean_execution, 'output': compile_output},
                                           len(plan['compile_targets'])) == 'passed', 'Baseline compilation is not clean')
        driver = campaign.driver_summary(test_output)
        counts = campaign.counts(test_output)
        _require(driver is not None and driver['plan'] == key
                 and [group['name'] for group in driver['groups']] == plan['groups'], 'Baseline driver plan differs')
        _require(counts is not None and all(_integer(counts.get(field)) for field in ('tests','assertions','failures','errors'))
                 and counts['tests'] > 0 and counts['assertions'] > 0 and counts['failures'] == counts['errors'] == 0,
                 'Baseline tests are not clean')
        _require(not driver.get('warnings') and not driver.get('timed_out') and not driver.get('containment_unknown'),
                 'Baseline driver did not complete cleanly')
        _require(_same(row.get('driver_summary'), driver), 'Pasted baseline summary differs from raw log')
        if prefix == 'baseline':
            _require(all(type(row.get(field)) is int and row[field] == value for field, value in counts.items())
                     and row.get('driver_plan') == key and row.get('driver_groups') == plan['groups'],
                     'Recorded baseline counts differ')
        elif any(field in row for field in counts):
            _require(all(type(row.get(field)) is int and row[field] == value for field, value in counts.items()),
                     'Recorded restoration counts differ')
        if previous is not None:
            prior = previous[key]
            _require(campaign.classify_test({**clean_execution, 'output': test_output}, prior) == 'survived'
                     and [group['tests'] for group in driver['groups']] ==
                         [group['tests'] for group in prior['driver_summary']['groups']],
                     'Restoration differs from the clean test plan')
        checked[key] = {**counts, 'driver_plan': key, 'driver_groups': plan['groups'], 'driver_summary': driver}
    return checked


def _ref_matches(reference, actual, *, next_index=False):
    keys = {'path', 'sha256', 'next_index'} if next_index else {'path', 'sha256'}
    _require(isinstance(reference, dict) and set(reference) == keys
             and all(_same(reference[key], actual[key]) for key in keys), 'Evidence reference mismatch')


def load_history(campaign, report_path, *, candidates, selection, producer, harness):
    """Validate every observed row and ancestor, preserving the entire prefix."""
    return _load_history(campaign, report_path, candidates, selection, producer, harness, set())


def _load_history(campaign, report_path, candidates, selection, producer, harness, seen):
    path = str(Path(report_path))
    _require(path not in seen and len(seen) < MAX_ANCESTORS, 'Cyclic or excessive continuation history')
    seen = seen | {path}
    _require(Path(path).name == 'report.json', 'History must select a canonical report')
    reference = _document(campaign, path)
    data, directory = reference['document'], Path(path).parent
    _require(data.get('source_sha') == campaign.SOURCE_SHA and data.get('application_under_test') == campaign.SOURCE_SHA,
             'History application source differs')
    _require(_same(data.get('selection'), selection) and selection.get('schema') == 'axxium-mutation-selection/v1',
             'History selection differs')
    _require(_same(data.get('producer'), producer) and _same(data.get('test_harness'), harness), 'History producer or harness differs')
    _require(_text(data.get('operator_id')), 'History lacks a declared operator')
    _require(_same(data.get('batch_index'), selection['batch_index'])
             and _same(data.get('sample_seed'), selection['sample_seed']),
             'History selection metadata differs')
    start = data.get('observed_prefix_start')
    _require(_integer(start) and start <= len(candidates), 'Invalid observed prefix start')
    _require(type(data.get('candidate_inventory')) is int and data['candidate_inventory'] == len(candidates)
             and type(data.get('execution_inventory')) is int
             and data['execution_inventory'] == len(candidates) - start, 'History inventory count differs')
    _require(type(data.get('completed_selection')) is bool and data.get('qualified_batch') is False,
             'History must retain explicit non-admission flags')
    _require(selection.get('ordered_fingerprints') == [row['fingerprint'] for row in candidates], 'Candidate order differs')
    inventory_fingerprints = selection.get('inventory_fingerprints')
    _require(isinstance(inventory_fingerprints, list)
             and all(isinstance(fp, str) and HEX.fullmatch(fp) for fp in inventory_fingerprints)
             and inventory_fingerprints == sorted(set(inventory_fingerprints))
             and set(selection['ordered_fingerprints']) <= set(inventory_fingerprints), 'Malformed complete inventory')
    _require(selection.get('source_sha') == campaign.SOURCE_SHA
             and selection.get('profile') in PROFILES
             and selection.get('inventory_sha256') == _hash(_canonical(selection.get('inventory_fingerprints')))
             and selection.get('selection_sha256') == _hash(_canonical(selection.get('ordered_fingerprints'))),
             'Malformed selection manifest')
    plans, mapping = _plans(campaign, candidates, harness)
    _require(data.get('candidate_test_plans') == mapping, 'Candidate test-plan map differs')
    saved_plans = data.get('test_plans')
    _require(isinstance(saved_plans, dict) and set(saved_plans) == set(plans), 'History plans differ')
    for key, expected in plans.items():
        _require(all(saved_plans[key].get(field) == expected[field] for field in ('key','compile_targets','groups')),
                 'Historical static test plan differs')
    baselines = _clean_baselines(campaign, directory, data.get('baselines'), plans, prefix='baseline')
    rows = data.get('results')
    _require(isinstance(rows, list) and start + len(rows) <= len(candidates), 'Invalid observed result prefix')
    for index, row in enumerate(rows):
        _require(isinstance(row, dict), 'Malformed result row')
        candidate = candidates[start + index]
        _require(all(_same(row.get(key), value) for key, value in candidate.items()), 'Observed candidate differs from ordered selection')
        key = mapping[candidate['fingerprint']]
        _require(row.get('test_plan') == key, 'Observed test plan differs')
        output = _log(campaign, directory, f'{index:04}.compile.log', row.get('compile_log_sha256'))
        build = _result(row, 'compile', output)
        status = campaign.classify_compile(build, len(plans[key]['compile_targets']))
        _require(row.get('compile_status') == status, 'Recorded compiler classification differs')
        if status == 'passed':
            _require(row.get('phase') == 'test', 'Successful compile lacks test execution')
            output = _log(campaign, directory, f'{index:04}.test.log', row.get('test_log_sha256'))
            result = _result(row, 'test', output)
            expected_status = campaign.classify_test(result, baselines[key])
            _require(row.get('status') == expected_status and _same(row.get('counts'), campaign.counts(output))
                     and _same(row.get('driver_summary'), _safe_driver(campaign, output)), 'Recorded test result differs from raw execution')
        else:
            _require(row.get('phase') == 'compile' and row.get('status') == status, 'Invalid compiler-only result')
            _require(not any(field.startswith('test_') and field != 'test_plan' for field in row), 'Unexecuted test evidence present')
        _require(row.get('status') in {'compile_failure','timeout','containment_unknown','killed','survived',
                                      'test_error','invalid_test_output','invalid_test_exit'}, 'Unknown result status')
        aborted = build['containment_unknown'] or (status == 'passed' and result['containment_unknown'])
        _require(not aborted or index == len(rows) - 1, 'Execution continued after an unacknowledged abort')
        _require(not aborted or data.get('containment') == 'unknown_after_aborted_execution',
                 'Aborted execution lacks a containment block')
    next_index = start + len(rows)
    _require(_integer(data.get('observed_prefix_end')) and data['observed_prefix_end'] == next_index,
             'Recorded observed prefix end differs')
    _require(_same(data.get('unexecuted_fingerprints'),
                   [candidate['fingerprint'] for candidate in candidates[next_index:]]),
             'Unexecuted source selection tail differs')
    last_unknown = bool(rows and (rows[-1].get('compile_containment_unknown') is True
                        or (rows[-1].get('phase') == 'test'
                            and rows[-1].get('test_containment_unknown') is True)))
    expected_terminal = ('aborted' if last_unknown else
                         'complete' if data['completed_selection'] else 'clean_partial')
    _require(data.get('terminal_state') == expected_terminal
             and (data.get('containment') == 'unknown_after_aborted_execution') == last_unknown,
             'History is running or has inconsistent terminal containment')
    _require(not data['completed_selection'] or next_index == len(candidates), 'False complete selection')
    if not last_unknown or data.get('restored_baseline') == 'passed':
        _require(data.get('restored_baseline') == 'passed', 'Complete selection lacks restoration')
        _clean_baselines(campaign, directory, data.get('restored_baselines'), plans, prefix='restored', previous=baselines)
    continuation = data.get('continuation')
    refs = []
    if continuation is None:
        _require(start == 0, 'Initial history skips candidates')
    else:
        _require(isinstance(continuation, dict) and set(continuation) == {'prior_report','containment_ack','restoration'},
                 'Malformed continuation evidence')
        prior = continuation['prior_report']
        _require(isinstance(prior, dict) and isinstance(prior.get('path'), str), 'Missing parent report')
        parent = _load_history(campaign, prior['path'], candidates, selection, producer, harness, seen)
        _ref_matches(prior, parent, next_index=True)
        _require(start == parent['next_index'] and start > 0, 'Continuation does not resume the cumulative prefix')
        ack = validate_ack(campaign, continuation['containment_ack'].get('path'), parent)
        _ref_matches(continuation['containment_ack'], ack)
        restored = validate_restoration(campaign, continuation['restoration'].get('path'), history=parent,
                                       ack=ack, candidates=candidates, selection=selection, producer=producer, harness=harness)
        _ref_matches(continuation['restoration'], restored)
        refs = parent['report_refs'] + [{'path': parent['path'], 'sha256': parent['sha256'], 'next_index': parent['next_index']}]
    return {**reference, 'next_index': next_index, 'report_refs': refs}


def validate_ack(campaign, ack_path, history):
    """Validate binding and declarations; this does not authenticate an inspector."""
    ack = _document(campaign, ack_path)
    data = ack['document']; marker = data.get('marker'); prior = history['document']
    _require(Path(ack['path']).name.startswith('containment-ack-'), 'Not a canonical archived acknowledgement')
    _require(isinstance(marker, dict) and marker.get('source_sha') == campaign.SOURCE_SHA
             and marker.get('state') == 'execution_active_or_containment_unknown'
             and _integer(marker.get('operator_pid'), 1) and _integer(marker.get('started_ns'), 1), 'Invalid acknowledged marker')
    _require(_integer(data.get('acknowledged_ns'), marker['started_ns'] + 1)
             and _integer(data.get('acknowledger_pid'), 1), 'Acknowledgement predates its execution')
    _require(_text(data.get('inspection_author'))
             and data['inspection_author'].strip() != prior.get('operator_id', '').strip()
             and _text(data.get('inspection_note'), 15, 8192), 'Independent inspection declaration missing')
    _require(data.get('attestation') == 'operator assertion, not independently verified', 'Acknowledgement overstates authentication')
    rows = prior.get('results')
    _require(isinstance(rows, list) and rows and prior.get('containment') == 'unknown_after_aborted_execution',
             'History does not end in an aborted execution')
    row = rows[-1]; phase = row['phase']
    _require(row.get(phase + '_containment_unknown') is True, 'Last execution was not aborted')
    expected = Path(history['path']).parent / f'{len(rows)-1:04}.{phase}.log'
    _require(marker.get('logfile') == str(expected), 'Acknowledgement does not bind the last aborted log')
    _log(campaign, expected.parent, expected.name, row.get(phase + '_log_sha256'))
    return ack


def validate_restoration(campaign, restoration_path, *, history, ack, candidates, selection, producer, harness):
    """Reparse hashed clean native logs before allowing a continuation."""
    reference = _document(campaign, restoration_path)
    data = reference['document']
    _require(data.get('schema') == 'axxium-campaign-restoration/v1' and data.get('source_sha') == campaign.SOURCE_SHA
             and _same(data.get('selection'), selection) and _same(data.get('producer'), producer)
             and _same(data.get('test_harness'), harness), 'Restoration custody differs')
    _ref_matches(data.get('prior_report'), history, next_index=True)
    _ref_matches(data.get('containment_ack'), ack)
    _require(data.get('status') == 'passed' and data.get('warnings') == [] and data.get('timed_out') is False,
             'Restoration is not clean')
    _require(_integer(data.get('started_ns'), ack['document']['acknowledged_ns'] + 1)
             and _integer(data.get('completed_ns'), data['started_ns']), 'Restoration predates acknowledgement')
    plans, _ = _plans(campaign, candidates, harness)
    _clean_baselines(campaign, Path(reference['path']).parent, data.get('baselines'), plans,
                     prefix='restore', previous=history['document']['baselines'])
    return reference
