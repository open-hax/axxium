"""Real temporary Git custody and filesystem guards for the external harness."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('external_harness', Path(__file__).with_name('external_harness.py'))
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


class ExternalHarnessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Harness fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.write('README.md', 'unchanged repository bytes\n')
        self.write('shadow-cljs.edn', '{:source-paths ["src/cljs"] :builds {}}\n')
        self.write('package.json', '{"name":"fixture"}\n')
        self.write('package-lock.json', '{"lockfileVersion":3}\n')
        self.write('scripts/existing.test.mjs', 'import "../target/test.cjs";\n')
        for support in harness.TEST_SUPPORT_PATHS:
            self.write(support, "import assert from 'node:assert/strict'; assert(true);\n")
        self.write('public/index.html', '<p>existing fixture</p>\n')
        self.write('src/cljs/axxium/core.cljs', '(ns axxium.core)\n(def enabled true)\n')
        self.write('src/cljs/axxium/other.cljc', '(ns axxium.other)\n')
        self.write('src/cljs/axxium/unchanged_test.cljc', '(ns axxium.unchanged-test)\n')
        self.added = 'src/cljs/axxium/extern/authorization_boundaries_test.cljs'
        for path in harness.CHANGED_TEST_PATHS - {self.added}:
            self.write(path, '; application baseline test\n')
        self.application = self.commit('application')
        for path in harness.CHANGED_TEST_PATHS:
            self.write(path, '; accepted regression coverage\n')
        self.regressions = self.commit('accepted test harness')
        self.git('checkout', '-q', '--detach', self.application)
        (self.root / 'node_modules').mkdir()
        application = mock.patch.object(harness, 'APPLICATION_SHA', self.application)
        regression = mock.patch.object(harness, 'HARNESS_SHA', self.regressions)
        application.start();regression.start()
        self.addCleanup(application.stop);self.addCleanup(regression.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE).decode().strip()

    def write(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)

    def commit(self, message):
        self.git('add', '--all')
        self.git('commit', '-q', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def prepare(self):
        return harness.prepare(self.root, self.application, self.regressions)

    def test_generated_project_has_one_live_source_tree_and_accepted_tests(self):
        descriptor = self.prepare()
        project = Path(descriptor['project_dir'])
        link = project / 'src/axxium/core.cljs'
        self.assertTrue(link.is_symlink())
        self.assertEqual(os.readlink(link), str(self.root / 'src/cljs/axxium/core.cljs'))
        self.assertEqual((project / 'src/axxium/extern/authorization_boundaries_test.cljs').read_text(),
                         '; accepted regression coverage\n')
        self.assertFalse((project / 'src/axxium/unchanged_test.cljc').is_symlink())
        self.assertFalse((project / 'scripts/existing.test.mjs').is_symlink())
        self.assertEqual((project / 'scripts/existing.test.mjs').read_bytes(),
                         (self.root / 'scripts/existing.test.mjs').read_bytes())
        self.assertEqual((project / 'shadow-cljs.edn').read_text(), '{:source-paths ["src"] :builds {}}\n')
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.application)
        self.assertEqual(self.git('diff', '--name-only', 'HEAD'), '')
        self.assertEqual(set(descriptor['changed_test_paths']), harness.CHANGED_TEST_PATHS)
        self.assertEqual(len(descriptor['non_test_tree_sha256']), 64)
        self.assertEqual(self.prepare(), descriptor)

    def test_current_application_mutant_is_visible_and_not_misclassified_as_harness_drift(self):
        descriptor = self.prepare()
        self.write('src/cljs/axxium/core.cljs', '(ns axxium.core)\n(def enabled false)\n')
        harness.validate(self.root, descriptor)
        self.assertIn('false', (Path(descriptor['project_dir']) / 'src/axxium/core.cljs').read_text())
        project = Path(descriptor['project_dir'])
        for name in harness.BUILD_DIRECTORIES:
            (project / name).mkdir()
            (project / name / 'compiler-output').write_text('not input source')
        harness.validate(self.root, descriptor)

    def test_non_test_repository_change_or_mode_change_refuses_before_project_creation(self):
        self.git('checkout', '-q', '--detach', self.regressions)
        self.write('README.md', 'different non-CLJS repository bytes\n')
        bad = self.commit('unaccepted repository change')
        self.git('checkout', '-q', '--detach', self.application)
        with mock.patch.object(harness, 'HARNESS_SHA', bad), self.assertRaises(harness.HarnessError):
            harness.prepare(self.root, self.application, bad)
        self.git('checkout', '-q', '--detach', self.regressions)
        (self.root / 'README.md').chmod(0o755)
        bad = self.commit('unaccepted mode change')
        self.git('checkout', '-q', '--detach', self.application)
        with mock.patch.object(harness, 'HARNESS_SHA', bad), self.assertRaises(harness.HarnessError):
            harness.prepare(self.root, self.application, bad)
        self.assertFalse((self.root / 'qualification/harness').exists())

    def test_additional_test_outside_the_exact_accepted_eight_refuses(self):
        self.git('checkout', '-q', '--detach', self.regressions)
        self.write('src/cljs/axxium/unreviewed_test.cljs', '; extra test\n')
        bad = self.commit('unreviewed test')
        self.git('checkout', '-q', '--detach', self.application)
        with mock.patch.object(harness, 'HARNESS_SHA', bad), self.assertRaises(harness.HarnessError):
            harness.prepare(self.root, self.application, bad)

    def test_unexpected_source_revision_or_harness_argument_refuses(self):
        for application, regression in [('a' * 40, self.regressions), (self.application, 'b' * 40)]:
            with self.subTest(application=application, regression=regression), self.assertRaises(harness.HarnessError):
                harness.prepare(self.root, application, regression)
        self.git('checkout', '-q', '--detach', self.regressions)
        with self.assertRaises(harness.HarnessError):
            self.prepare()

    def test_partial_existing_project_is_preserved_and_never_overwritten(self):
        project = self.root / 'qualification/harness' / self.regressions
        project.mkdir(parents=True)
        sentinel = project / 'partial-input'
        sentinel.write_bytes(b'preserve incomplete evidence')
        with self.assertRaises((harness.HarnessError, OSError)):
            self.prepare()
        self.assertEqual(sentinel.read_bytes(), b'preserve incomplete evidence')

    def test_immutable_copy_or_mode_drift_refuses_reuse(self):
        descriptor = self.prepare()
        project = Path(descriptor['project_dir'])
        for name in ['src/axxium/config_test.cljs', 'shadow-cljs.edn', 'package.json', 'package-lock.json',
                     'scripts/existing.test.mjs', 'public/index.html']:
            path = project / name
            original = path.read_bytes()
            path.write_bytes(original + b'changed')
            with self.subTest(path=name), self.assertRaises(harness.HarnessError):
                self.prepare()
            path.write_bytes(original)
        path = project / 'package.json'
        path.chmod(0o755)
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)

    def test_source_and_dependency_link_redirection_refuse(self):
        descriptor = self.prepare()
        project = Path(descriptor['project_dir'])
        path = project / 'src/axxium/core.cljs'
        path.unlink();path.symlink_to(self.root / 'src/cljs/axxium/other.cljc')
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)
        path.unlink();path.symlink_to(self.root / 'src/cljs/axxium/core.cljs')
        dependency = project / 'node_modules'
        dependency.unlink();dependency.symlink_to(self.root)
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)

    def test_unexpected_generated_source_and_descriptor_changes_refuse(self):
        descriptor = self.prepare()
        project = Path(descriptor['project_dir'])
        extra = project / 'src/untracked.cljs'
        extra.write_text('(ns untracked)')
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)
        extra.unlink()
        for key, value in [('root', '/unrelated'), ('project_dir', str(self.root)), ('harness_sha', self.application)]:
            altered = copy.deepcopy(descriptor);altered[key] = value
            with self.subTest(key=key), self.assertRaises(harness.HarnessError):
                harness.validate(self.root, altered)
        record = project / harness.DESCRIPTOR
        record.write_text(json.dumps(descriptor))
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)

    def test_symlinked_project_or_parent_is_not_followed(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.root / 'qualification').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(harness.HarnessError):
            self.prepare()
        self.assertEqual(list(outside.iterdir()), [])

    def test_support_directory_redirection_or_extra_input_refuses(self):
        descriptor = self.prepare()
        project = Path(descriptor['project_dir'])
        extra = project / 'public/additional.html'
        extra.write_text('untracked input')
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)
        extra.unlink()
        script = project / 'scripts/existing.test.mjs'
        for path in [script, *(project / support for support in harness.TEST_SUPPORT_PATHS)]:
            path.unlink()
        script.parent.rmdir()
        (project / 'scripts').symlink_to(self.root / 'scripts', target_is_directory=True)
        with self.assertRaises(harness.HarnessError):
            harness.validate(self.root, descriptor)

    def test_second_test_only_head_is_selected_without_reinterpreting_older_project(self):
        self.git('checkout', '-q', '--detach', self.regressions)
        added = 'src/cljs/axxium/extern/qualification_boundaries_test.cljs'
        self.write(added, '; accepted public boundary contracts\n')
        self.write('src/cljs/axxium/law/oidc_test.cljs', '; strengthened boundary coverage\n')
        second = self.commit('accepted second test-only head')
        self.git('checkout', '-q', '--detach', self.application)
        first = self.prepare()
        with mock.patch.object(harness, 'BOUNDARY_HARNESS_SHA', second):
            descriptor = harness.prepare(self.root, self.application, second)
            self.assertEqual(second, descriptor['harness_sha'])
            self.assertEqual(harness.CHANGED_TEST_PATHS | {added}, set(descriptor['changed_test_paths']))
            self.assertEqual(first['non_test_tree_sha256'], descriptor['non_test_tree_sha256'])
            self.assertNotEqual(first['project_dir'], descriptor['project_dir'])
            self.assertEqual('; accepted public boundary contracts\n',
                             (Path(descriptor['project_dir']) / 'src/axxium/extern/qualification_boundaries_test.cljs').read_text())
            harness.validate(self.root, first)
            harness.validate(self.root, descriptor)
            self.assertEqual(first, self.prepare())

    def test_second_head_still_refuses_non_test_or_extra_test_changes(self):
        for path in ['README.md', 'src/cljs/axxium/unaccepted_test.cljs']:
            self.git('checkout', '-q', '--detach', self.regressions)
            self.write('src/cljs/axxium/extern/qualification_boundaries_test.cljs', '; accepted boundary tests\n')
            self.write(path, '; additional unaccepted input\n')
            bad = self.commit('unaccepted second head input')
            self.git('checkout', '-q', '--detach', self.application)
            with mock.patch.object(harness, 'BOUNDARY_HARNESS_SHA', bad), self.assertRaises(harness.HarnessError):
                harness.prepare(self.root, self.application, bad)

    def test_descriptor_cannot_select_an_unknown_harness_or_a_non_string(self):
        descriptor = self.prepare()
        for identity in ['b' * 40, [], None]:
            altered = copy.deepcopy(descriptor)
            altered['harness_sha'] = identity
            with self.subTest(identity=identity), self.assertRaises(harness.HarnessError):
                harness.validate(self.root, altered)

    def protocol_commit(self, extra=None):
        self.git('checkout', '-q', '--detach', self.regressions)
        self.write('src/cljs/axxium/extern/qualification_boundaries_test.cljs', '; accepted public contracts\n')
        for support in harness.TEST_SUPPORT_PATHS:
            self.write(support, "import assert from 'node:assert/strict'; assert.ok(true);\n")
        if extra:
            self.write(extra, '; unaccepted support input\n')
        revision = self.commit('accepted protocol test support')
        self.git('checkout', '-q', '--detach', self.application)
        return revision

    def test_closed_protocol_support_uses_accepted_test_bytes_and_retains_application_scripts(self):
        revision = self.protocol_commit()
        first = self.prepare()
        with mock.patch.object(harness, 'PROTOCOL_HARNESS_SHA', revision):
            descriptor = harness.prepare(self.root, self.application, revision)
            self.assertEqual(sorted(harness.TEST_SUPPORT_PATHS), descriptor['test_support_paths'])
            self.assertNotIn('test_support_paths', first)
            self.assertEqual(harness.CHANGED_TEST_PATHS | harness.TEST_SUPPORT_PATHS |
                             {'src/cljs/axxium/extern/qualification_boundaries_test.cljs'},
                             set(descriptor['changed_test_paths']))
            for support in harness.TEST_SUPPORT_PATHS:
                self.assertEqual('test-support', descriptor['files'][support]['kind'])
                self.assertIn('assert.ok(true)', (Path(descriptor['project_dir']) / support).read_text())
                self.assertIn(' assert(true)', (self.root / support).read_text())
            harness.validate(self.root, first)
            harness.validate(self.root, descriptor)
            self.assertEqual(first, self.prepare())
            support = Path(descriptor['project_dir']) / sorted(harness.TEST_SUPPORT_PATHS)[0]
            support.write_text('; changed generated test support\n')
            with self.assertRaises(harness.HarnessError):
                harness.validate(self.root, descriptor)

    def test_protocol_support_exception_does_not_admit_other_repository_changes(self):
        for extra in ('scripts/existing.test.mjs', 'README.md', 'src/cljs/axxium/unreviewed_test.cljs'):
            revision = self.protocol_commit(extra)
            with mock.patch.object(harness, 'PROTOCOL_HARNESS_SHA', revision), self.assertRaises(harness.HarnessError):
                harness.prepare(self.root, self.application, revision)

    def test_missing_known_protocol_support_input_refuses(self):
        revision = self.protocol_commit()
        self.git('checkout', '-q', '--detach', revision)
        self.git('rm', sorted(harness.TEST_SUPPORT_PATHS)[0])
        missing = self.commit('missing test support')
        self.git('checkout', '-q', '--detach', self.application)
        with mock.patch.object(harness, 'PROTOCOL_HARNESS_SHA', missing), self.assertRaises(harness.HarnessError):
            harness.prepare(self.root, self.application, missing)


if __name__ == '__main__':
    unittest.main()
