"""Native subprocess/control-flow fixtures; these reports are never qualification evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import campaign_lineage as lineage
import external_harness
import mutation_campaign as campaign


class ContinuationFlowTests(unittest.TestCase):
    def test_timeout_ack_fresh_restoration_and_exact_suffix_preserve_all_observations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'src/cljs/axxium/example.cljc'
            source.parent.mkdir(parents=True)
            original = '(defn value [] 7)\n'
            source.write_text(original)
            (root / 'package-lock.json').write_text('{"lockfileVersion":3}\n')
            for args in (['init', '-q'], ['config', 'user.name', 'Fixture'],
                         ['config', 'user.email', 'fixture@example.invalid'],
                         ['config', 'core.hooksPath', '/dev/null'], ['add', 'src', 'package-lock.json'],
                         ['commit', '--no-gpg-sign', '-qm', 'fixture']):
                subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
            harness = {'harness_sha': 'e'*40, 'project_dir': str(root), 'kind': 'explicit test fixture'}
            producer = {'revision': 'c'*40, 'files': {str(i): 'd'*64 for i in range(6)}}
            compiler = [sys.executable, '-c', "print('Build completed. (0 warnings)')"]
            actual_probe = subprocess.check_output

            def probe(command, **options):
                if command == [sys.executable, '--version']:
                    return 'v22.23.3'
                return actual_probe(command, **options)

            with mock.patch.object(campaign, 'ROOT', root), mock.patch.object(campaign, 'SOURCE', source.parent), \
                    mock.patch.object(campaign, 'SOURCE_SHA', revision), mock.patch.object(campaign, 'STOP_REQUESTED', False), \
                    mock.patch.object(campaign.subprocess, 'check_output', side_effect=probe), \
                    mock.patch.object(campaign, 'producer_custody', return_value=producer), \
                    mock.patch.object(external_harness, 'prepare', return_value=harness), \
                    mock.patch.object(external_harness, 'validate'):
                candidates, selection = lineage.select_candidates(campaign, profile='standard-v2',
                    path_prefix='src/cljs/', batch_index=None, sample_seed=None)
                first = candidates[0]
                timeout_text = original[:first['char_start']] + first['after'] + original[first['char_end']:]
                tester = root / 'qualification/test-fixture.py'
                tester.parent.mkdir()
                tester.write_text('''import json,pathlib,time
p=pathlib.Path('src/cljs/axxium/example.cljc');text=p.read_text()
if text==''' + repr(timeout_text) + ''': time.sleep(30)
failed=0
try: assert text==''' + repr(original) + '''
except AssertionError: failed=1
group=dict(name='cljs',tests=1,assertions=1,failures=failed,errors=0,warnings=[],timed_out=False)
summary=dict(schema='axxium-qualification-test-phase/v1',plan='cljs',groups=[group],
 tests=1,assertions=1,failures=failed,errors=0,warnings=[],timed_out=False,containment_unknown=False)
print('QUALIFICATION_TEST_SUMMARY='+json.dumps(summary),flush=True)
raise SystemExit(failed)
''')
                test_command = [sys.executable, str(tester)]

                def plans(items, _harness, _node, _compiler, _root, _test=None):
                    return ({'cljs': {'key': 'cljs', 'compile_targets': ['test'], 'groups': ['cljs'],
                            'compile_command': compiler, 'test_command': test_command}},
                            {row['fingerprint']: 'cljs' for row in items})

                def invoke(*args):
                    with mock.patch.object(sys, 'argv', ['mutation_campaign.py', '--path-prefix', 'src/cljs/',
                            '--profile', 'standard-v2', '--external-harness-sha', 'e'*40,
                            '--producer-revision', 'c'*40, '--operator-id', 'fixture-controller', *map(str, args)]):
                        campaign.main()

                with mock.patch.object(campaign, 'campaign_plans', side_effect=plans), \
                        mock.patch.object(campaign, 'pinned_commands', return_value=(sys.executable, compiler, test_command)):
                    initial = root / 'qualification/results/initial/report.json'
                    # Cold Python startup under the one-CPU control unit may
                    # exceed 200 ms; the intentional mutant still blocks 30 s.
                    with self.assertRaisesRegex(RuntimeError, 'containment unknown'):
                        campaign.run_campaign(candidates, initial.parent, 1000, 1,
                            external_harness_sha='e'*40, producer_revision='c'*40,
                            selection=selection, operator_id='fixture-controller')
                    self.assertEqual(source.read_text(), original)
                    initial_bytes = initial.read_bytes()
                    history = lineage.load_history(campaign, initial, candidates=candidates, selection=selection,
                                                   producer=producer, harness=harness)
                    self.assertEqual(history['next_index'], 1)
                    self.assertEqual(history['document']['results'][0]['status'], 'timeout')
                    self.assertFalse(history['document']['qualified_batch'])
                    self.assertTrue(campaign.containment_marker().exists())
                    with self.assertRaisesRegex(RuntimeError, 'containment unknown'):
                        invoke('--continue-from', initial, '--out', 'qualification/results/blocked')
                    self.assertFalse((root/'qualification/results/blocked').exists())
                    # Explicit fixture declaration: authentic independent process inspection is
                    # an operational requirement, not a fact authenticated by these strings.
                    ack_path = campaign.acknowledge_containment(
                        'Fixture native timeout process group terminated; temporary source restored.', 'fixture-inspector')
                    ack = lineage.validate_ack(campaign, ack_path, history)
                    restored_path = root / 'qualification/results/restoration/restoration.json'
                    invoke('--restore-from', initial.relative_to(root), '--containment-ack', ack_path.relative_to(root),
                           '--out', restored_path.parent)
                    restored_bytes = restored_path.read_bytes()
                    self.assertEqual(json.loads(restored_bytes)['status'], 'passed')
                    self.assertFalse(json.loads(restored_bytes)['qualification_credit'])
                    changed = copy.deepcopy(json.loads(restored_bytes)); changed['prior_report']['next_index'] = 0
                    campaign.save_json(restored_path, changed)
                    with self.assertRaises(ValueError):
                        invoke('--continue-from', initial.relative_to(root), '--containment-ack', ack_path.relative_to(root),
                               '--restoration', restored_path.relative_to(root), '--out', 'qualification/results/rejected')
                    self.assertFalse((root/'qualification/results/rejected').exists())
                    campaign.private_write(restored_path, restored_bytes.decode())
                    redirect = root / 'qualification/results/redirect'
                    redirect.symlink_to(initial.parent, target_is_directory=True)
                    for unsafe in (Path('qualification/results/initial/../initial/report.json'),
                                   Path('qualification/results/redirect/report.json')):
                        with self.subTest(unsafe=str(unsafe)), self.assertRaises(ValueError):
                            invoke('--continue-from', unsafe, '--containment-ack', ack_path.relative_to(root),
                                   '--restoration', restored_path.relative_to(root),
                                   '--out', 'qualification/results/unsafe')
                        self.assertFalse((root/'qualification/results/unsafe').exists())
                    suffix = root / 'qualification/results/suffix/report.json'
                    invoke('--continue-from', initial.relative_to(root), '--containment-ack', ack_path.relative_to(root),
                           '--restoration', restored_path.relative_to(root), '--limit', 1000, '--out', suffix.parent)
                    complete = lineage.load_history(campaign, suffix, candidates=candidates, selection=selection,
                                                    producer=producer, harness=harness)
                    result = complete['document']
                    self.assertEqual(result['observed_prefix_start'], 1)
                    self.assertEqual(complete['next_index'], len(candidates))
                    self.assertEqual([r['fingerprint'] for r in result['results']],
                                     [r['fingerprint'] for r in candidates[1:]])
                    self.assertEqual(result['unexecuted_fingerprints'], [])
                    self.assertEqual(result['terminal_state'], 'complete')
                    self.assertFalse(result['qualified_batch'])
                    self.assertEqual(complete['report_refs'], [{'path': str(initial),
                        'sha256': campaign.digest(initial_bytes), 'next_index': 1}])
                    self.assertEqual(initial.read_bytes(), initial_bytes)
                    self.assertEqual(restored_path.read_bytes(), restored_bytes)
                    self.assertEqual(source.read_text(), original)
                    self.assertFalse(campaign.containment_marker().exists())
                    campaign.require_unchanged_tree()


if __name__ == '__main__':
    unittest.main()
