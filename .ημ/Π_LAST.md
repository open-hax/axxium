# Π handoff

- time: 2026-04-18T02:49:45Z
- branch: pi/fork-tax/2026-04-15-170411
- pre-Π HEAD: ea56c668b907

## Summary
- Fork-tax snapshot for devel superproject (origin: git@github.com:riatzukiza/devel.git).
- **430 stale root-level files deleted**: old specs, scripts, images, debug files, absorbed project dirs (pm2-clj-project, reconstitute, hormuz_clock_v4_bundle, labs, web, etc.)
- **.gitmodules cleanup**: removed 21 stale submodule entries for paths no longer on disk.
- **Config updates**: opencode plugin bump 1.2.26→1.4.3, new model variants in models.example.json, tooloxx services in pnpm-workspace.yaml, pnpm-lock.yaml sync.
- **Submodule pointers updated** (pushed only): eta-mu @ 3e384556, ragussy @ 04bf0c2e.

## Verification
- skipped (no new run during this Π step; rely on receipts.log for prior passing builds/tests).

## Concurrent/unowned dirt left untouched
- `.spacemacs` — typechange from symlink to file (personal config)
- `.ημ/03_ARTIFACTS/narrative_audio` — dirty submodule (modified content)
- 9 submodules with unpushed commits and/or dirty working trees:
  - uxx (9 ahead), shuvcrawl (4 ahead), openplanner (1 ahead + dirty), proxx (4 ahead + dirty), eros-eris-field (ahead + dirty), eros-eris-field-app (ahead + dirty), agent-actors (8 ahead + dirty), vexx (ahead + dirty), our-gpus (12 ahead + dirty)

## Notes
- Π capture is isolated to this branch; main is not rewritten.
- Gitlink deletions for removed submodules (internal/open-hax, internal/riatzukiza, etc.) are committed.
- Some gitlinks remain for submodules still present on disk (bevy_replicon, egregoria, ggrs) but not in .gitmodules — these are legacy and harmless.
