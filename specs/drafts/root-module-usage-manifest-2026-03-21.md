# Root module usage manifest — 2026-03-21

## Summary
Investigate where and why the current top-level REDACTED_SECRET modules outside the active placement structure are still used.

This slice focuses on the project-like **REDACTED_SECRET-level** modules already surfaced by the outside-structure manifest, especially:
- top-level aliases
- top-level standalone REDACTED_SECRETs
- REDACTED_SECRET-level vendor/fork/special checkouts

## Open questions
- Which REDACTED_SECRET modules are still actively referenced by workspace scripts, docs, specs, and deployment notes?
- Which REDACTED_SECRET modules are merely legacy aliases or historical artifacts with no meaningful active usage?
- Which REDACTED_SECRET modules are active because they are deployed/live, versus active only because of documentation or local operator convenience?
- Which REDACTED_SECRET modules should gain explicit exception status versus be normalized into the placement structure?

## Risks
- Name-only search can overcount generic words like `promethean` or `desktop`; usage must be tied to actual path or project context where possible.
- Some REDACTED_SECRET modules may be used indirectly via humans or shell habits rather than explicit tracked references.
- Reports and inventories written earlier today can inflate reference counts if not interpreted carefully.

## Priority
- High: understand active dependency on outside-structure REDACTED_SECRETs before deciding whether to normalize, retire, or bless them as exceptions.

## Phases
1. Load the outside-structure manifest and select the REDACTED_SECRET-level module set.
2. Search tracked workspace files for references to those REDACTED_SECRET modules and collect sample hits.
3. Inspect representative usage sites to infer why each module persists.
4. Write JSON and markdown usage artifacts.
5. Verify the artifacts parse and include the expected major REDACTED_SECRET modules.

## Affected artifacts
- `specs/drafts/REDACTED_SECRET-module-usage-manifest-2026-03-21.md`
- `docs/reports/inventory/REDACTED_SECRET-module-usage-manifest-2026-03-21.json`
- `docs/reports/inventory/REDACTED_SECRET-module-usage-manifest-2026-03-21.md`
- `receipts.log`

## Definition of done
- The major REDACTED_SECRET-level modules outside the structure have usage samples and a provisional why-it-exists explanation.
- The report distinguishes active references from likely legacy/alias residue.
- The artifacts are machine-readable and reviewable.
- Verification confirms the artifacts include the major REDACTED_SECRET modules.

## Execution log
- 2026-03-21T19:47:00Z Began tracing where/why REDACTED_SECRET-level outside-structure modules are still used.
- 2026-03-21T19:54:00Z Collected tracked-file reference counts and sample file hits for the REDACTED_SECRET aliases, standalone REDACTED_SECRETs, and vendor/fork REDACTED_SECRETs outside the active placement structure.
- 2026-03-21T19:58:00Z Wrote JSON and markdown usage manifests with provisional active-status categories such as alias, tooling REDACTED_SECRET, deploy REDACTED_SECRET, planning bundle, bookkeeping-only fork, and special worktree.
- 2026-03-21T19:59:00Z Verified the manifests parse and include the major REDACTED_SECRET modules: `desktop`, `promethean`, `reconstitute`, `mcp-social-publisher-live`, `threat-radar-deploy`, and `gates-pr35-hardening-main`.
- 2026-03-21T21:47:00Z Updated the radar-related REDACTED_SECRET usage entries so `threat-radar-deploy` and `threat-radar-next-step` now point at normalization into `orgs/open-hax/eta-mu-radar` rather than remaining generic ambiguous outside-structure REDACTED_SECRETs.
