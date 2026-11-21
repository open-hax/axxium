"""Check Work source selection and the native adoption boundary."""
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native_campaign_job as native
import work_campaign as work


class WorkCampaignTest(unittest.TestCase):
    def test_closed_selection_refuses_changed_source_and_order(self):
        """A Work invocation cannot choose a different app, index, or inventory."""
        count, digest = work.PARTITIONS[0]
        expected = {'selection_sha256': digest, 'inventory_sha256': work.INVENTORY,
                    'inventory_fingerprints': range(6342)}
        campaign = SimpleNamespace(SOURCE_SHA=work.APPLICATION)
        lineage = SimpleNamespace(select_candidates=lambda *_args, **_kwargs: (range(count), expected))
        self.assertEqual(work.select(campaign, lineage, 0), (range(count), expected))
        for source, index in (('a'*40, 0), (work.APPLICATION, True), (work.APPLICATION, 4)):
            with self.subTest(source=source, index=index), self.assertRaises(ValueError):
                work.select(SimpleNamespace(SOURCE_SHA=source), lineage, index)
        expected['selection_sha256'] = 'f'*64
        with self.assertRaisesRegex(ValueError, 'frozen input'):
            work.select(campaign, lineage, 0)

    def test_public_native_read_has_no_redirect_or_credential(self):
        """Only a bounded successful response from the fixed GitHub host is accepted."""
        with patch.object(native, 'HTTPSConnection') as factory:
            connection = factory.return_value
            response = connection.getresponse.return_value
            response.status = 200
            response.read.return_value = b'{"id":52}'
            self.assertEqual(native.public_work_api('pulls/52'), {'id': 52})
            factory.assert_called_once_with('api.github.com', timeout=20)
            args, kwargs = connection.request.call_args
            self.assertEqual(args, ('GET', '/repos/open-hax/axxium/pulls/52'))
            self.assertNotIn('Authorization', kwargs['headers'])
            for status in (302, 403):
                response.status = status
                with self.subTest(status=status), self.assertRaisesRegex(ValueError, 'metadata is unavailable'):
                    native.public_work_api('pulls/52')

    def test_work_adoption_requires_merged_pins_and_matching_runtime_bytes(self):
        """The accepted controller, workflow, worker and scanner bind the same bytes."""
        revision, head, scan_merge, fixture_merge = '1'*40, '2'*40, '3'*40, '4'*40
        origin = {'controller_sha': revision, 'workflow': work.WORKFLOW}
        repo = {'full_name': native.collector.REPOSITORY}

        def pr(number, head_sha, merge):
            return {'number': number, 'merged': True, 'merge_commit_sha': merge,
                    'base': {'ref': 'main', 'repo': repo}, 'head': {'sha': head_sha, 'repo': repo}}

        scanner = pr(53, work.SCANNER_HEAD, scan_merge)
        fixtures = pr(55, work.HARNESS, fixture_merge)
        responses = {'pulls/52': pr(52, 'd138923acdcd036fd7d1f4be3dbc964daf590f28', work.APPLICATION),
                     'pulls/53': scanner, 'pulls/54': pr(54, head, revision), 'pulls/55': fixtures,
                     'commits/'+revision+'/pulls?per_page=100': [{'number': 54}]}
        for accepted in (work.APPLICATION, work.SCANNER, work.SCANNER_HEAD, scan_merge, revision,
                         work.HARNESS, fixture_merge):
            responses['compare/'+accepted+'...main'] = {'merge_base_commit': {'sha': accepted}}
        with tempfile.TemporaryDirectory() as temporary:
            controller = Path(temporary)
            collector_path = controller/'qualification/collect_mutation_evidence.py'
            wrapper_path = controller/work.ENTRYPOINT
            runtime = ['qualification/'+name for name in native.collector.RUNTIME_FILES]
            files = (*runtime, work.WORKFLOW, work.ENTRYPOINT, 'qualification/native_campaign_job.py',
                     'qualification/collect_mutation_evidence.py')
            for name in files:
                path = controller/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            changed = set()

            def git(argv, *, cwd):
                self.assertEqual(cwd, controller)
                commit, name = argv[-1].split(':', 1)
                return b'changed' if (commit, name) in changed else name.encode()

            with (patch.object(native, 'CONTROLLER', controller),
                  patch.object(native.collector, '__file__', str(collector_path)),
                  patch.object(work, '__file__', str(wrapper_path)),
                  patch.object(native.subprocess, 'check_output', side_effect=git),
                  patch.object(native.collector, 'verify_evaluator_custody', return_value={
                      Path(name).name: native.collector.sha(name.encode()) for name in runtime})):
                receipt = native.verify_work_adoption(origin, api=responses.__getitem__)
                self.assertEqual(set(receipt['controller_file_sha256']), set(files))
                self.assertEqual(receipt['scanner_pr_head'], work.SCANNER_HEAD)
                self.assertEqual(receipt['harness_sha'], work.HARNESS)
                self.assertEqual(receipt['harness_merge_sha'], fixture_merge)
                self.assertEqual(set(receipt['harness_fixture_sha256']), work.HARNESS_PATHS)
                for merged, sha in ((False, work.HARNESS), (True, 'f'*40)):
                    with self.subTest(merged=merged, sha=sha), self.assertRaisesRegex(ValueError, 'fixture adoption differs'):
                        responses['pulls/55'] = {**pr(55, sha, fixture_merge), 'merged': merged}
                        native.verify_work_adoption(origin, api=responses.__getitem__)
                responses['pulls/55'] = fixtures
                changed.add((fixture_merge, 'scripts/lifecycle.test.mjs'))
                with self.assertRaisesRegex(ValueError, 'Merged Work fixture differs'):
                    native.verify_work_adoption(origin, api=responses.__getitem__)
                changed.clear()
                changed.add((scan_merge, 'qualification/mutation_campaign.py'))
                with self.assertRaisesRegex(ValueError, 'scanner bytes differ'):
                    native.verify_work_adoption(origin, api=responses.__getitem__)
                changed.clear()
                changed.add((head, work.ENTRYPOINT))
                with self.assertRaisesRegex(ValueError, 'input differs'):
                    native.verify_work_adoption(origin, api=responses.__getitem__)
                scanner['merged'] = False
                with self.assertRaisesRegex(ValueError, 'scanner adoption differs'):
                    native.verify_work_adoption(origin, api=responses.__getitem__)

    def test_vendored_work_sources_get_the_canonical_cljs_plan(self):
        """The whole selection includes these two tracked files and no arbitrary vendor paths."""
        driver = native.collector.test_phase
        for path in ('src/cljs/clio/shape/canonical.cljc', 'src/cljs/clio/shape/edn.cljc'):
            self.assertEqual(driver.plan_for_path(path), driver.plan('cljs'))
        with self.assertRaises(ValueError):
            driver.plan_for_path('src/cljs/clio/shape/arbitrary.cljc')
