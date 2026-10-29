"""Boundary tests for the candidate runner; fixture outcomes are not evidence."""
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import json

import mutation_campaign as campaign


class ReaderTests(unittest.TestCase):
    def test_excludes_comment_string_regex_quote_discard_and_character_literals(self):
        source = r'''(= a b) ; (= comment here)
"(= string content)" #"(= regex)" '(= quoted 0) `(~(+ 1 2) (= quoted 0))
#_(= discarded 0) (comment (= ignored 0)) {\" "quoted" \' "apostrophe"}
(and true (not= x y))'''
        found = [(before, after) for _, _, before, after, _ in campaign.sites(source)]
        self.assertEqual(found, [("=", "not="), ("and", "or"), ("true", "false"), ("not=", "=")])

    def test_unbalanced_and_truncated_forms_are_rejected(self):
        for source in ('(= 1', '[(= 1 2)}', '"unterminated', "\\"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                campaign.sites(source)


class OutcomeTests(unittest.TestCase):
    def setUp(self):
        self.base = {"tests": 2, "assertions": 3, "failures": 0, "errors": 0}
        self.clean = "Ran 2 tests containing 3 assertions.\n0 failures, 0 errors."
        self.failed = "FAIL in (test-a)\nRan 2 tests containing 3 assertions.\n1 failures, 0 errors."

    def result(self, output, exit_code=0, timed_out=False):
        return {"output": output, "exit_code": exit_code, "timed_out": timed_out}

    def test_only_completed_assertion_failure_counts_as_kill(self):
        self.assertEqual(campaign.classify_test(self.result(self.failed, 126), self.base), "killed")
        self.assertEqual(campaign.classify_test(self.result(self.clean), self.base), "survived")
        self.assertEqual(campaign.classify_test(self.result(self.failed, 0), self.base), "invalid_test_exit")
        self.assertEqual(campaign.classify_test(self.result(self.failed.replace('0 errors','1 errors'), 1), self.base), "test_error")
        self.assertEqual(campaign.classify_test(self.result(self.failed.replace('Ran 2','Ran 1'), 1), self.base), "invalid_test_output")
        self.assertEqual(campaign.classify_test(self.result(self.failed, 1, True), self.base), "timeout")
        self.assertEqual(campaign.classify_test(self.result('Error: connection failed', 1), self.base), "invalid_test_output")

    def test_compile_failure_and_warning_never_count_as_kills(self):
        good = "Build completed. (31 files, 0 compiled, 0 warnings, 1.23s)"
        self.assertEqual(campaign.classify_compile(self.result(good)), "passed")
        self.assertEqual(campaign.classify_compile(self.result(good, 1)), "compile_failure")
        self.assertEqual(campaign.classify_compile(self.result(good.replace('0 warnings','1 warnings'))), "compile_failure")
        self.assertEqual(campaign.classify_compile(self.result(good, timed_out=True)), "timeout")

    def test_timeout_kills_child_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "orphan"
            logfile = Path(directory) / "log"
            command = ['python3', '-c',
                       "import subprocess,time,sys; subprocess.Popen([sys.executable,'-c',sys.argv[1]]); time.sleep(60)",
                       "import time,pathlib; time.sleep(1); pathlib.Path(" + repr(str(marker)) + ").write_text('bad')"]
            result = campaign.execute(command, logfile, timeout=0.2)
            self.assertTrue(result['timed_out'])
            time.sleep(1.1)
            self.assertFalse(marker.exists(), "subprocess survived timed-out process group")

    def test_restore_refuses_to_overwrite_independent_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source'
            path.write_bytes(b'mutated')
            campaign.restore(path, b'original', b'mutated')
            self.assertEqual(path.read_bytes(), b'original')
            path.write_bytes(b'independent')
            with self.assertRaisesRegex(RuntimeError, 'concurrently'):
                campaign.restore(path, b'original', b'mutated')
            self.assertEqual(path.read_bytes(), b'independent')

    def test_output_and_batch_boundaries(self):
        result = campaign.owned_output(Path('qualification/results/new-pilot'))
        self.assertTrue(result.is_relative_to((campaign.ROOT / 'qualification/results').resolve()))
        for path in [Path('/tmp/axxium-evidence'), Path('qualification/results/../../outside')]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                campaign.owned_output(path)
        fingerprints = [f'{i:064x}' for i in range(20)]
        partitions = [{fp for fp in fingerprints if campaign.batch_for(fp) == i} for i in range(4)]
        self.assertEqual(set.union(*partitions), set(fingerprints))
        for i, left in enumerate(partitions):
            for right in partitions[i + 1:]:
                self.assertFalse(left & right)

    def test_only_one_cooperating_campaign_can_hold_checkout_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'ROOT', Path(directory)):
                with campaign.campaign_lock():
                    with self.assertRaisesRegex(RuntimeError, 'Another mutation pilot'):
                        with campaign.campaign_lock():
                            self.fail('second lock must refuse')

    def test_recovery_checks_revision_and_does_not_clobber_independent_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'src/cljs/example.cljc'
            source.parent.mkdir(parents=True)
            journal = root / 'qualification/results/fixture/inflight.json'
            journal.parent.mkdir(parents=True)
            original, changed = b'original', b'mutation'
            entry = {'path': 'src/cljs/example.cljc', 'source_sha': campaign.SOURCE_SHA,
                     'original_sha256': campaign.digest(original), 'mutant_sha256': campaign.digest(changed)}
            source.write_bytes(b'independent')
            journal.write_text(json.dumps(entry))
            with patch.object(campaign, 'ROOT', root), patch.object(campaign, 'git', return_value=original):
                with self.assertRaisesRegex(ValueError, 'independently'):
                    campaign.recover(journal)
                self.assertEqual(source.read_bytes(), b'independent')
                source.write_bytes(changed)
                journal.write_text(json.dumps({**entry, 'source_sha': '0' * 40}))
                with self.assertRaisesRegex(ValueError, 'different Git revision'):
                    campaign.recover(journal)
                journal.write_text(json.dumps(entry))
                campaign.recover(journal)
                self.assertEqual(source.read_bytes(), original)
                self.assertFalse(journal.exists())
                self.assertTrue(journal.with_name('recovered-inflight.json').exists())


if __name__ == "__main__":
    unittest.main()
