"""Boundary tests for the candidate runner; fixture outcomes are not evidence."""
import tempfile
import importlib.util
import os
import signal
import subprocess
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import json

import mutation_campaign as campaign


class ReaderTests(unittest.TestCase):
    def test_import_needs_git_but_does_not_probe_node_or_volta(self):
        probes = []

        def git_only(command, **_options):
            probes.append(command)
            if command == ["git", "rev-parse", "HEAD"]:
                return "a" * 40
            raise AssertionError("Unit-test import must not resolve an execution runtime")

        spec = importlib.util.spec_from_file_location("mutation_import_fixture", campaign.__file__)
        module = importlib.util.module_from_spec(spec)
        with patch("subprocess.check_output", side_effect=git_only):
            spec.loader.exec_module(module)
        self.assertEqual(module.SOURCE_SHA, "a" * 40)
        self.assertEqual(probes, [["git", "rev-parse", "HEAD"]])

    def test_excludes_comment_string_regex_quote_discard_and_character_literals(self):
        source = r'''(= a b) ; (= comment here)
"(= string content)" #"(= regex)" '(= quoted 0) `(~(+ 1 2) (= quoted 0))
#_(= discarded 0) (comment (= ignored 0)) {\" "quoted" \' "apostrophe"}
(and true (not= x y))'''
        found = [(before, after) for _, _, before, after, _ in campaign.sites(source)]
        self.assertEqual(found, [("=", "not="), ("and", "or"), ("true", "false"), ("not=", "=")])

    def test_expanded_operators_and_integer_boundaries_are_syntactic_only(self):
        source = '(not x) (string? x) (map? x) (even? x) (empty? x) [0 1 42 -4] ; 9\n #_123 "999"'
        found = [(before, after) for _, _, before, after, _ in campaign.sites(source)]
        for pair in [('not', 'identity'), ('string?', 'number?'), ('map?', 'vector?'),
                     ('even?', 'odd?'), ('empty?', 'seq'), ('0', '-1'), ('0', '1'),
                     ('1', '0'), ('1', '2'), ('42', '41'), ('42', '43'), ('-4', '-5'), ('-4', '-3')]:
            self.assertIn(pair, found)
        self.assertFalse(any(before in ('9', '123', '999') for before, _ in found))
        self.assertEqual(len(found), len(set(found)))
        self.assertEqual(campaign.atom_replacements('1.0'), ())
        self.assertEqual(campaign.atom_replacements('1000000000'), ())

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
        self.assertEqual(campaign.classify_test(self.result(self.failed, 1), self.base), "killed")
        for code in (-9, 126, 137):
            with self.subTest(exit_code=code):
                self.assertEqual(campaign.classify_test(self.result(self.failed, code), self.base), "invalid_test_exit")
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
        for count in (1, 10, 20, 100):
            with self.subTest(warnings=count):
                self.assertEqual(campaign.classify_compile(self.result(good.replace("0 warnings", f"{count} warnings"))), "compile_failure")
        self.assertEqual(campaign.classify_compile(self.result(good, timed_out=True)), "timeout")

    def test_timeout_kills_child_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            pidfile = Path(directory) / "descendant.pid"
            logfile = Path(directory) / "log"
            command = ['python3', '-c',
                       "import subprocess,time,sys; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); open(sys.argv[1],'w').write(str(p.pid)); time.sleep(60)",
                       str(pidfile)]
            try:
                with patch.object(campaign, "ROOT", Path(directory)):
                    result = campaign.execute(command, logfile, timeout=2)
                self.assertTrue(result['timed_out'])
                self.assertTrue(pidfile.exists(), "descendant never started; timeout test is vacuous")
                pid = int(pidfile.read_text())
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    try:
                        os.kill(pid, 0)
                        state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
                        if state == "Z":
                            break  # Reparented zombies may await init's reaper.
                    except (ProcessLookupError, FileNotFoundError):
                        break
                    time.sleep(0.05)
                else:
                    self.fail("descendant survived timed-out process group")
            finally:
                if pidfile.exists():
                    try:
                        os.kill(int(pidfile.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_timeout_with_escaped_session_has_bounded_capture_and_unknown_containment(self):
        with tempfile.TemporaryDirectory() as directory:
            pidfile = Path(directory) / "escaped.pid"
            command = ["python3", "-c",
                       "import subprocess,time,sys; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True); open(sys.argv[1],'w').write(str(p.pid)); time.sleep(60)",
                       str(pidfile)]
            started = time.monotonic()
            try:
                with patch.object(campaign, "ROOT", Path(directory)):
                    result = campaign.execute(command, Path(directory) / "log", timeout=0.4)
                    with self.assertRaisesRegex(RuntimeError, "containment unknown"):
                        campaign.require_no_containment_marker()
                self.assertLess(time.monotonic() - started, 2.5)
                self.assertTrue(result["timed_out"])
                self.assertTrue(result["containment_unknown"])
                self.assertEqual(campaign.classify_test(result, self.base), "timeout")
                self.assertTrue(pidfile.exists())
                os.kill(int(pidfile.read_text()), 0)  # The escaped process is not claimed cleaned up.
            finally:
                if pidfile.exists():
                    try:
                        os.kill(int(pidfile.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_signal_interruption_retains_block_after_restoration(self):
        module_dir = str(Path(campaign.__file__).parent)
        helper = r"""
import os, pathlib, signal, sys
sys.path.insert(0, sys.argv[1])
import mutation_campaign as m
m.ROOT = pathlib.Path(sys.argv[2])
for sig in (signal.SIGINT, signal.SIGTERM): signal.signal(sig, m.interrupted)
source = m.ROOT / 'source'
source.write_bytes(b'original')
with m.campaign_lock():
    m.require_no_containment_marker()
    source.write_bytes(b'mutated')
    try:
        m.execute([sys.executable, '-c', "import subprocess,time,sys; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True); open(sys.argv[1],'w').write(str(p.pid)); time.sleep(60)", str(m.ROOT / 'escaped.pid')], m.ROOT / 'output', 30)
    finally:
        m.restore(source, b'original', b'mutated')
"""
        for sig in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=sig), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                child = subprocess.Popen([sys.executable, '-c', helper, module_dir, directory],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                pidfile = root / 'escaped.pid'
                try:
                    deadline = time.monotonic() + 10
                    while not pidfile.exists() and time.monotonic() < deadline:
                        if child.poll() is not None: self.fail('helper exited before escaped child started')
                        time.sleep(0.02)
                    self.assertTrue(pidfile.exists())
                    child.send_signal(sig)
                    child.wait(timeout=3)
                    self.assertEqual((root / 'source').read_bytes(), b'original')
                    self.assertTrue((root / 'qualification/results/containment-unknown.json').exists())
                    os.kill(int(pidfile.read_text()), 0)  # Escaped child really is still alive.
                    blocked = subprocess.run([sys.executable, '-c',
                        "import pathlib,sys; sys.path.insert(0,sys.argv[1]); import mutation_campaign as m; m.ROOT=pathlib.Path(sys.argv[2]); sys.argv=['pilot','--inventory']; m.main()",
                        module_dir, directory], capture_output=True, text=True, timeout=5)
                    self.assertNotEqual(blocked.returncode, 0)
                    self.assertIn('containment unknown', blocked.stderr)
                    with patch.object(campaign, 'ROOT', root):
                        with campaign.campaign_lock():  # Interruption released the lock.
                            with self.assertRaisesRegex(RuntimeError, 'containment unknown'):
                                campaign.require_no_containment_marker()
                            with self.assertRaises(ValueError): campaign.acknowledge_containment('short')
                            os.kill(int(pidfile.read_text()), signal.SIGKILL)
                            archive = campaign.acknowledge_containment('Terminated owned escaped test child')
                            self.assertTrue(archive.exists())
                            campaign.require_no_containment_marker()
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait(timeout=3)
                    if pidfile.exists():
                        try: os.kill(int(pidfile.read_text()), signal.SIGKILL)
                        except ProcessLookupError: pass

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
