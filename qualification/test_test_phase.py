"""Exercise native Node failures and infrastructure exclusions, without PG."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import test_phase as phase


def node_binary():
    selected = os.environ.get('AXXIUM_QUAL_TEST_NODE') or shutil.which('node')
    if not selected:
        raise RuntimeError('Node is required for native assertion classification tests')
    # Resolve a version-manager shim before replacing HOME for the fixture.
    return subprocess.check_output([selected, '-p', 'process.execPath'], text=True, timeout=10).strip()


class TestPhaseTests(unittest.TestCase):
    def test_selection_is_static_and_rejects_escape(self):
        self.assertEqual(phase.plan_for_path('src/cljs/axxium/law/oidc.cljs')['compile_targets'],
                         ['test', 'oidc-store-fixture'])
        self.assertEqual(phase.plan_for_path('src/cljs/axxium/config.cljs')['compile_targets'],
                         ['test', 'oidc-store-fixture', 'lifecycle-fixture'])
        self.assertEqual(phase.plan_for_path('src/cljs/axxium/render.cljs')['key'], 'cljs+browser')
        for path in ('../src/cljs/axxium/auth.cljs', '/src/cljs/axxium/auth.cljs',
                     'src/cljs/axxium/../auth.cljs', 'scripts/thing.py'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                phase.plan_for_path(path)
        for key in ('protocol', 'cljs+unknown', 'cljs+browser+protocol', 'cljs+cljs'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                phase.plan(key)

    def test_cljs_requires_completed_real_assertion_failure(self):
        text = 'FAIL in (fixture)\nRan 2 tests containing 3 assertions.\n1 failures, 0 errors.\n'
        self.assertEqual(phase.cljs_result(1, text), phase.result(2, 3, failures=1))
        for code, output in ((0, text), (126, text), (1, text.replace('FAIL in ', 'other ')),
                             (1, 'Error: database unavailable'), (1, text + text)):
            with self.subTest(code=code, output=output):
                self.assertGreater(phase.cljs_result(code, output)['errors'], 0)
        self.assertEqual(phase.cljs_result(0, text.replace('FAIL in (fixture)\n', '').replace(
            '1 failures', '0 failures'))['failures'], 0)

    def test_protocol_setup_and_plain_runtime_errors_are_never_kills(self):
        measured = dict(passed=0, failed=0, setup_failures=1, warnings=[])
        self.assertGreater(phase.protocol_result(1, '', measured)['errors'], 0)
        self.assertEqual(phase.protocol_result(1, '', measured)['failures'], 0)
        measured.update(setup_failures=0, passed=7)
        self.assertGreater(phase.protocol_result(1, 'TypeError: fixture startup', measured)['errors'], 0)
        measured.update(failed=1)
        actual = phase.protocol_result(1, 'AssertionError', measured)
        self.assertEqual((actual['tests'], actual['assertions'], actual['failures'], actual['errors']), (1, 8, 1, 0))

    def test_protocol_pass_requires_every_real_completion_marker(self):
        measured = dict(passed=400, failed=0, setup_failures=0, warnings=[])
        full = '\n'.join(phase.PROTOCOL_MARKERS)
        self.assertEqual(phase.protocol_result(0, full, measured)['errors'], 0)
        self.assertGreater(phase.protocol_result(0, full.replace(phase.PROTOCOL_MARKERS[0], ''), measured)['errors'], 0)
        measured['warnings'] = ['DeprecationWarning']
        self.assertEqual(phase.protocol_result(0, full, measured)['warnings'], ['DeprecationWarning'])

    def test_observed_fixture_error_denies_kill_even_alongside_response_assertion(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
process.emit('axxiumQualificationFixtureError');
assert.equal('text/plain', 'application/json');
''', filename='oidc-protocol.fixture.mjs', node_test=False)
        self.assertEqual(measured['setup_failures'], 1)
        self.assertEqual(measured['failed'], 1)
        actual = phase.protocol_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual((actual['failures'], actual['errors']), (1, 1))
        import mutation_campaign as campaign
        group = {'name': 'protocol', **actual}
        summary = {'schema': 'axxium-qualification-test-phase/v1', 'plan': 'protocol',
                   'groups': [group], 'containment_unknown': False, **actual}
        result = {'exit_code': 1, 'timed_out': False,
                  'output': phase.PREFIX + json.dumps(summary)}
        baseline = {'tests': 1, 'assertions': 1, 'driver_plan': 'protocol', 'driver_groups': ['protocol']}
        self.assertEqual(campaign.classify_test(result, baseline), 'test_error')

    def test_foreign_fixture_error_event_cannot_relabel_a_genuine_contract_assertion(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
process.emit('axxiumQualificationFixtureError');
assert.equal(1, 2);
''', filename='actor-pages.test.mjs', node_test=False)
        self.assertEqual(measured['setup_failures'], 0)
        self.assertEqual(measured['failed'], 1)
        self.assertEqual(phase.protocol_result(run.returncode, run.stdout + run.stderr, measured)['errors'], 0)

    def test_fresh_protocol_baseline_after_error_observation_has_no_error_credit(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
assert.equal(1, 1);
''' + ''.join('console.log(' + json.dumps(marker) + ');\n' for marker in phase.PROTOCOL_MARKERS),
            filename='oidc-protocol.fixture.mjs', node_test=False)
        self.assertEqual(measured['setup_failures'], 0)
        self.assertEqual(phase.protocol_result(run.returncode, run.stdout + run.stderr, measured),
                         phase.result(1, 1))

    def native(self, code, filename='actor-pages.test.mjs', *, node_test=True):
        node = node_binary()
        with tempfile.TemporaryDirectory(prefix='aq-law-', dir='/tmp') as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            output = root / 'counts'
            output.mkdir(mode=0o700)
            script = root / 'scripts' / filename
            script.write_text(code)
            env = {'PATH': os.defpath, 'HOME': str(root), 'LANG': 'C.UTF-8',
                   'AXXIUM_QUAL_SOURCE_ROOT': str(root), 'AXXIUM_QUAL_COUNTER_DIR': str(output),
                   'NODE_OPTIONS': '--import=' + (phase.HERE / 'assertion_measurement.mjs').as_uri()}
            args = [node]
            if node_test:
                args += ['--test', '--test-reporter=' + str(phase.HERE / 'node_test_reporter.mjs')]
            run = subprocess.run(args + [str(script)], cwd=root, env=env,
                                 capture_output=True, text=True, timeout=20)
            return run, phase.counters(output)

    def test_native_node_reporter_distinguishes_assertions_and_plain_errors(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
import test from 'node:test';
test('pass', () => assert.equal(1, 1));
test('assertion', () => assert.equal(1, 2));
test('runtime', () => { throw new TypeError('fixture runtime'); });
''')
        actual = phase.node_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual((actual['tests'], actual['assertions'], actual['failures'], actual['errors']), (3, 2, 1, 1))
        self.assertFalse(actual['timed_out'])

    def test_native_assertion_is_measured_and_counts_as_a_failed_test(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
import test from 'node:test';
test('assertion', () => assert.equal(1, 2));
''')
        actual = phase.node_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual((actual['tests'], actual['failures'], actual['errors']), (1, 1, 0))
        self.assertEqual(measured['failed'], 1)

    def test_native_node_timeout_stays_a_timeout(self):
        run, measured = self.native('''
import test from 'node:test';
test('timeout', {timeout: 10}, async () => new Promise(() => {}));
''')
        actual = phase.node_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertTrue(actual['timed_out'])
        self.assertEqual(actual['failures'], 0)

    def test_native_pg_tool_assertion_is_infrastructure_not_an_application_kill(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
function run() { assert.equal(1, 0, 'pg_ctl fixture failed'); }
run();
''', 'oidc-store.integration.mjs', node_test=False)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual((measured['failed'], measured['setup_failures']), (0, 1))
        actual = phase.protocol_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual(actual['failures'], 0)
        self.assertGreater(actual['errors'], 0)

    def test_native_protocol_assertion_is_counted(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
assert.equal(1, 2, 'real protocol fixture assertion');
''', 'oidc-protocol.fixture.mjs', node_test=False)
        actual = phase.protocol_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual((actual['tests'], actual['failures'], actual['errors']), (1, 1, 0))

    def test_missing_lifecycle_child_before_readiness_is_not_a_kill(self):
        run, measured = self.native('''
import assert from 'node:assert/strict';
import test from 'node:test';
import {spawn} from 'node:child_process';
import {setTimeout} from 'node:timers/promises';
test('fixture readiness', async () => {
  const child = spawn(process.execPath, ['missing-child.fixture.mjs'], {stdio: 'ignore'});
  async function readUntil() { await setTimeout(60); assert.match('', /LISTEN/); }
  try { await readUntil(); } finally { child.kill('SIGKILL'); }
});
''', 'lifecycle.test.mjs')
        self.assertEqual((measured['failed'], measured['setup_failures']), (0, 1))
        actual = phase.node_result(run.returncode, run.stdout + run.stderr, measured)
        self.assertEqual(actual['failures'], 0)
        self.assertGreater(actual['errors'], 0)

    def test_missing_or_forged_counter_refuses(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                phase.counters(directory)
            path = Path(directory) / '123.json'
            path.write_text(json.dumps({'schema': 'axxium-test-assertion-measurement/v1',
                'passed': {'fixture': True}, 'failed': {}, 'setup_failures': {}, 'warnings': []}))
            with self.assertRaises(ValueError):
                phase.counters(directory)

    def cljs_execution(self, source):
        node = node_binary()
        with tempfile.TemporaryDirectory(prefix='aq-run-', dir='/tmp') as temporary:
            project = Path(temporary)
            (project / 'target').mkdir()
            (project / 'target/test.cjs').write_text(source)
            with patch('builtins.print') as printed:
                value = phase.execute('cljs', node, project)
            self.assertTrue(all(str(call.args[0]).startswith('QUALIFICATION_CHILD[cljs] ')
                                for call in printed.call_args_list))
            return value

    def test_native_cljs_warning_cannot_be_hidden_by_assertion_failure(self):
        actual = self.cljs_execution('''
process.emitWarning('fixture runtime warning');
console.log('FAIL in (fixture)');
console.log('Ran 2 tests containing 3 assertions.');
console.log('1 failures, 0 errors.');
process.exitCode = 1;
''')
        self.assertEqual(actual['failures'], 1)
        self.assertIn('Warning', actual['warnings'])

    def test_child_capture_is_bounded_and_excess_output_never_qualifies(self):
        actual = self.cljs_execution('''
process.stdout.write('x'.repeat(2 * 1024 * 1024) + '\\n');
console.log('FAIL in (fixture)');
console.log('Ran 2 tests containing 3 assertions.');
console.log('1 failures, 0 errors.');
process.exitCode = 1;
''')
        self.assertGreater(actual['errors'], 0)
        self.assertIn('ChildOutputLimit', actual['warnings'])
        self.assertGreater(actual['groups'][0]['child_output_bytes'], phase.MAX_CHILD_CAPTURE)


if __name__ == '__main__':
    unittest.main()
