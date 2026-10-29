"""Run source-bound assertion suites; infrastructure errors never supply kills.

The campaign owns compilation, outer deadlines and process containment. Children
remain in its process group. Only this driver emits the structured final marker.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
PREFIX = 'QUALIFICATION_TEST_SUMMARY='
NODE_PREFIX = 'AXXIUM_NODE_TEST_SUMMARY='
GROUP_ORDER = ('cljs', 'protocol', 'lifecycle', 'browser')
PROTOCOL_MARKERS = ('PASS durable authority:', 'PASS mounted RFC8628:',
                    'PASS mounted Fastify authority:', 'PASS isolated PostgreSQL:')
MAX_CHILD_CAPTURE = 1024 * 1024


def plan_for_path(path):
    value = PurePosixPath(path)
    if (value.is_absolute() or '..' in value.parts or str(value) != path or
            not path.startswith('src/cljs/axxium/') or value.suffix not in {'.cljs', '.cljc'}):
        raise ValueError('Unsupported application mutation path')
    groups = {'cljs'}
    if any(part in value.stem for part in ('oidc', 'config', 'auth', 'session', 'relay', 'pg')):
        groups.add('protocol')
    if value.stem in {'lifecycle', 'server', 'config'}:
        groups.add('lifecycle')
    if any(part in path for part in ('render', 'actor_pages', 'oidc_return', '/routes')):
        groups.add('browser')
    key = '+'.join(group for group in GROUP_ORDER if group in groups)
    return plan(key)


def plan(key):
    groups = key.split('+')
    if ('cljs' not in groups or groups != [g for g in GROUP_ORDER if g in groups]
            or len(groups) != len(set(groups))):
        raise ValueError('Unsupported test plan')
    targets = ['test']
    if 'protocol' in groups:
        targets.append('oidc-store-fixture')
    if 'lifecycle' in groups:
        targets.append('lifecycle-fixture')
    return {'key': key, 'compile_targets': targets, 'groups': groups}


def result(tests, assertions, failures=0, errors=0, *, warnings=None, timed_out=False):
    return dict(tests=tests, assertions=assertions, failures=failures, errors=errors,
                warnings=warnings or [], timed_out=timed_out)


def cljs_result(code, output):
    summaries = re.findall(r'^Ran (\d+) tests containing (\d+) assertions\.$', output, re.M)
    outcomes = re.findall(r'^(\d+) failures, (\d+) errors\.$', output, re.M)
    if len(summaries) != 1 or len(outcomes) != 1:
        return result(0, 0, errors=1)
    tests, assertions = map(int, summaries[0])
    failures, errors = map(int, outcomes[0])
    if (code not in {0, 1} or tests < 1 or assertions < 1 or
            (failures and (code != 1 or 'FAIL in ' not in output)) or
            (not failures and not errors and code != 0) or
            (code == 0 and (failures or errors))):
        errors += 1
    return result(tests, assertions, failures, errors)


def counters(directory):
    total = {'passed': 0, 'failed': 0, 'setup_failures': 0, 'warnings': []}
    files = list(Path(directory).glob('*.json'))
    if not files or len(files) > 64:
        raise ValueError('Assertion measurement unavailable')
    for path in files:
        if path.is_symlink() or not re.fullmatch(r'[0-9]+\.json', path.name) or path.stat().st_size > 65536:
            raise ValueError('Invalid assertion measurement')
        value = json.loads(path.read_text())
        if value.get('schema') != 'axxium-test-assertion-measurement/v1':
            raise ValueError('Unknown assertion measurement')
        for name, field in [('passed', 'passed'), ('failed', 'failed'), ('setup_failures', 'setup_failures')]:
            table = value.get(field)
            if not isinstance(table, dict) or any(type(v) is not int or v < 0 for v in table.values()):
                raise ValueError('Invalid assertion counts')
            total[name] += sum(table.values())
        warnings = value.get('warnings')
        if not isinstance(warnings, list) or any(not isinstance(v, str) for v in warnings):
            raise ValueError('Invalid runtime warning observation')
        total['warnings'].extend(warnings)
    return total


def protocol_result(code, output, measured):
    failures = measured['failed']
    errors = int(bool(measured['setup_failures']))
    if (code not in {0, 1} or (code == 0 and failures) or
            (code != 0 and not failures) or
            (not failures and (measured['passed'] < 1 or
                              not all(marker in output for marker in PROTOCOL_MARKERS)))):
        errors += 1
    return result(1, measured['passed'] + failures, failures, errors,
                  warnings=measured['warnings'])


def node_result(code, output, measured):
    rows = [line[len(NODE_PREFIX):] for line in output.splitlines() if line.startswith(NODE_PREFIX)]
    if len(rows) != 1:
        return result(0, measured['passed'] + measured['failed'], errors=1,
                      warnings=measured['warnings'])
    value = json.loads(rows[0])
    if (any(type(value.get(k)) is not int or value[k] < 0 for k in ('tests', 'failures', 'errors'))
            or type(value.get('timed_out')) is not bool):
        raise ValueError('Invalid native Node test result')
    errors = value['errors'] + int(bool(measured['setup_failures']))
    failures = max(0, value['failures'] - measured['setup_failures'])
    if (code not in {0, 1} or value['tests'] < 1 or
            (code == 0 and (value['failures'] or errors or value['timed_out'])) or
            (code != 0 and not (value['failures'] or errors or value['timed_out'])) or
            (failures and measured['failed'] < 1)):
        errors += 1
    return result(value['tests'], measured['passed'] + measured['failed'], failures, errors,
                  warnings=measured['warnings'], timed_out=value['timed_out'])


def child(args, project, environment, group):
    """Stream to the campaign's bounded capture, retaining at most one MiB."""
    process = subprocess.Popen(args, cwd=project, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    tail = bytearray()
    size, checksum = 0, hashlib.sha256()
    with process.stdout:
        for block in iter(lambda: process.stdout.read(8192), b''):
            size += len(block)
            checksum.update(block)
            tail.extend(block)
            if len(tail) > MAX_CHILD_CAPTURE:
                del tail[:-MAX_CHILD_CAPTURE]
            for line in block.decode('utf-8', errors='replace').splitlines():
                print('QUALIFICATION_CHILD[' + group + '] ' + line, flush=True)
    return process.wait(), tail.decode('utf-8', errors='replace'), size, checksum.hexdigest()


def execute(key, node, project):
    selected = plan(key)
    node, project = Path(node), Path(project).resolve()
    if not node.is_absolute() or not node.is_file() or not project.is_dir():
        raise ValueError('Pinned Node executable and generated project required')
    records = []
    with tempfile.TemporaryDirectory(prefix='aq-', dir='/tmp') as temporary:
        home = Path(temporary)
        environment = {'PATH': str(node.parent) + ':/usr/local/bin:/usr/bin:/bin',
                       'HOME': str(home), 'TMPDIR': str(home), 'LANG': 'C.UTF-8',
                       'NODE_OPTIONS': '--max-old-space-size=1024'}
        if os.environ.get('AXXIUM_TEST_PG_BIN'):
            environment['AXXIUM_TEST_PG_BIN'] = os.environ['AXXIUM_TEST_PG_BIN']
        for group in selected['groups']:
            env = environment.copy()
            directory = home / group
            directory.mkdir(mode=0o700)
            env.update(AXXIUM_QUAL_SOURCE_ROOT=str(project), AXXIUM_QUAL_COUNTER_DIR=str(directory),
                       NODE_OPTIONS=environment['NODE_OPTIONS'] + ' --import=' +
                       (HERE / 'assertion_measurement.mjs').as_uri())
            if group == 'cljs':
                args = [str(node), 'target/test.cjs']
            else:
                if group == 'protocol':
                    args = [str(node), 'scripts/oidc-store.integration.mjs']
                else:
                    files = (['scripts/lifecycle.test.mjs', 'scripts/compose-stop-grace.test.mjs']
                             if group == 'lifecycle' else
                             ['scripts/actor-pages.test.mjs', 'scripts/oidc-return.test.mjs'])
                    args = [str(node), '--test', '--test-reporter=' + str(HERE / 'node_test_reporter.mjs'), *files]
            # The campaign provides the deadline and containment for this entire
            # process group. Do not detach children or reinterpret its timeout.
            code, output, size, checksum = child(args, project, env, group)
            measured = counters(directory)
            if group == 'cljs':
                record = cljs_result(code, output)
                record['warnings'] = measured['warnings']
            else:
                record = (protocol_result if group == 'protocol' else node_result)(
                    code, output, measured)
            if size > MAX_CHILD_CAPTURE:
                record['errors'] += 1
                record['warnings'].append('ChildOutputLimit')
            record.update(child_output_bytes=size, child_output_sha256=checksum)
            records.append({'name': group, **record})
    summary = {k: sum(r[k] for r in records) for k in ('tests', 'assertions', 'failures', 'errors')}
    summary.update(schema='axxium-qualification-test-phase/v1', plan=key, groups=records,
                   warnings=[w for r in records for w in r['warnings']],
                   timed_out=any(r['timed_out'] for r in records), containment_unknown=False,
                   assertion_count_kind='CLJS assertions and measured named fixture assertions; bare assert calls excluded')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True)
    parser.add_argument('--node', required=True)
    parser.add_argument('--project', required=True)
    args = parser.parse_args()
    try:
        summary = execute(args.plan, args.node, args.project)
    except (OSError, ValueError, KeyError, TypeError):
        summary = {**result(0, 0, errors=1), 'schema': 'axxium-qualification-test-phase/v1',
                   'plan': args.plan, 'groups': [], 'containment_unknown': False}
    print(PREFIX + json.dumps(summary, sort_keys=True), flush=True)
    if summary['errors'] or summary['warnings'] or summary['timed_out']:
        return 2
    return 1 if summary['failures'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
