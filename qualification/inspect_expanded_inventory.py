"""Count hypothetical additional operators against pinned Git source blobs.

These operator ideas have not been reviewed or tested as mutations, and this
script explicitly emits no killed/survived or qualification claims.
"""
import collections
import json
import re
import subprocess
import mutation_campaign as m

paths = [p for p in m.SOURCE.rglob('*') if p.suffix in {'.cljs', '.cljc'}
         and not p.stem.endswith('_test') and not p.stem.endswith('_fixture')]
blobs = [(str(path.relative_to(m.ROOT)),
          subprocess.check_output(['git', 'show', 'HEAD:' + str(path.relative_to(m.ROOT))], cwd=m.ROOT).decode())
         for path in sorted(paths)]
assert m.git('rev-parse', 'HEAD').decode().strip() == m.SOURCE_SHA
base = sum(len(m.sites(source)) for _, source in blobs)
integers = set()
for _, source in blobs:
    for value, _, _ in m.tokens(source):
        if re.fullmatch(r'-?(0|[1-9][0-9]{0,9})', value):
            integers.add(value)
for value in integers:
    number = int(value)
    m.ATOM_REPLACEMENTS[value] = (str(number - 1), str(number + 1))
m.REPLACEMENTS.update({'not': ('identity',), 'string?': ('number?',), 'number?': ('integer?',),
                       'integer?': ('number?',), 'vector?': ('map?',), 'map?': ('vector?',),
                       'keyword?': ('string?',), 'empty?': ('seq',)})
counts = collections.Counter(site[4] for _, source in blobs for site in m.sites(source))
print(json.dumps({'source_sha': m.SOURCE_SHA, 'source_files_scanned': len(blobs),
                  'baseline_syntactic_candidates': base, 'expanded_syntactic_candidates': sum(counts.values()),
                  'expanded_by_kind': dict(counts), 'counts_are': 'unexecuted proposals, not valid or killed mutants',
                  'unverified_operators': 'integer boundaries and type/predicate substitutions'}, indent=2))
