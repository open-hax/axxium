"""Summarize syntactic mutation proposals, not evidence of non-equivalence."""
import collections
import json
import mutation_campaign as m

with m.campaign_lock():
    m.require_no_containment_marker()
    candidates = m.inventory()
    paths = collections.Counter(candidate['path'] for candidate in candidates)
    replacements = collections.Counter(f"{candidate['before']} -> {candidate['after']}" for candidate in candidates)
    partitions = collections.Counter(str(m.batch_for(candidate['fingerprint'])) for candidate in candidates)
    print(json.dumps({
        'source_sha': m.SOURCE_SHA,
        'syntactic_candidates': len(candidates),
        'source_files_with_candidates': len(paths),
        'by_path': dict(sorted(paths.items())),
        'by_replacement': dict(sorted(replacements.items())),
        'fingerprint_partitions': dict(sorted(partitions.items())),
        'counts_are': 'proposals only: compile validity, coverage and non-equivalence are not established',
        'qualified_batch': False,
    }, indent=2))
