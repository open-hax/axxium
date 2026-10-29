"""Declared mutation-family laws; these fixtures are not qualification evidence."""
import hashlib
import json
import unittest
from pathlib import Path
from unittest.mock import patch

import mutation_campaign as campaign


class ProfileTests(unittest.TestCase):
    def standard(self, source):
        return campaign.sites(source, campaign.STANDARD_PROFILE)

    def strings(self, source):
        return [row for row in self.standard(source) if row[-1] == "string_literal"]

    def test_complete_crcr_is_the_fixed_standard_set_without_noops_or_duplicates(self):
        for before, expected in [("64", {"1", "0", "-1", "-64", "65", "63"}),
                                 ("0", {"1", "-1"}), ("1", {"0", "2", "-1"}),
                                 ("-1", {"-2", "0", "1"})]:
            source = "(def amount " + before + ")"
            rows = [row for row in self.standard(source) if row[2] == before]
            self.assertEqual({row[3] for row in rows}, expected)
            self.assertEqual(len(rows), len(expected))
            self.assertNotIn(before, expected)

    def test_legacy_identities_are_preserved_inside_the_standard_superset(self):
        source = '(defn sample [n] (when (and true (<= n 64)) "response"))'
        old = campaign.sites(source, campaign.LEGACY_PROFILE)
        current = self.standard(source)
        self.assertTrue(set(old) < set(current))
        def identity(row):
            start, _, before, after, _ = row
            return hashlib.sha256(json.dumps(["a"*40, "src/cljs/example.cljc",
                hashlib.sha256(source.encode()).hexdigest(), len(source[:start].encode()), before, after],
                separators=(",", ":")).encode()).hexdigest()
        self.assertTrue({identity(row) for row in old} < {identity(row) for row in current})
        self.assertEqual(len(current), len({identity(row) for row in current}))

    def test_string_replacement_keeps_exact_escaped_multiline_and_unicode_spans(self):
        source = '(def message "hé\\\"llo\nline") (str "x" "")'
        rows = self.strings(source)
        self.assertEqual([row[2] for row in rows], ['"hé\\\"llo\nline"', '"x"'])
        for start, end, before, after, _ in rows:
            self.assertEqual(source[start:end], before)
            self.assertEqual(after, '""')
            raw = source.encode(); offset = len(source[:start].encode())
            self.assertEqual(raw[offset:offset+len(before.encode())], before.encode())

    def test_imports_docstrings_metadata_comments_regex_characters_and_quotes_are_inert(self):
        source = r'''(ns sample "namespace documentation" (:require ["node:fs" :as fs]))
        (def ^{:doc "metadata"} message "variable documentation" "runtime")
        (defn ^:async reply "function documentation" [] "response")
        (js/require "package") (.import loader "package")
        (comment "ignored") ; "line comment"
        '("quoted") `("syntax quoted") #"regex" \" #inst "2026-01-01"
        #_ "discarded" (str "active")'''
        self.assertEqual([row[2] for row in self.strings(source)], ['"runtime"', '"response"', '"active"'])

    def test_nested_discards_do_not_become_semantic_docstring_or_runtime_positions(self):
        source = '''#_ #_ "first" "second"
        (defn sample #_ "ignored" "documentation" [] "runtime")
        ' #_ "discarded before quoted value" "quoted"
        (def ordinary "value")'''
        self.assertEqual([row[2] for row in self.strings(source)], ['"runtime"', '"value"'])

    def test_symbolic_literals_empty_strings_and_unknown_profiles_are_not_invented_variants(self):
        self.assertEqual(self.strings('(str "" ##Inf ##NaN :keyword)'), [])
        with self.assertRaises(ValueError):
            campaign.sites('(str "value")', 'unreviewed-family')
        for source in ['(str "unterminated)', '(str "value"]']:
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.standard(source)

    def test_executed_lineage_module_is_part_of_the_six_file_producer_custody(self):
        directory = Path(campaign.__file__).parent
        def objects(*args):
            self.assertEqual(args[0], 'show')
            name = args[1].split(':qualification/', 1)[1]
            return (directory/name).read_bytes()
        with patch.object(campaign, 'git', side_effect=objects):
            result = campaign.producer_custody('a'*40, external=True)
        self.assertEqual(len(result['files']), 6)
        self.assertIn('campaign_lineage.py', result['files'])
        def changed(*args):
            return b'changed lineage' if args[1].endswith('/campaign_lineage.py') else objects(*args)
        with patch.object(campaign, 'git', side_effect=changed), self.assertRaisesRegex(ValueError, 'differs'):
            campaign.producer_custody('a'*40, external=True)


if __name__ == '__main__':
    unittest.main()
