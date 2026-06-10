(fork-tax-snapshot
  (time "20260610T042416Z")
  (repo "open-hax/axxium")
  (tag "fork-tax/20260610-ci-automation")
  (source-branch "feat/ci-automation-1781026522")
  (base "origin/staging")
  (snapshot-commit "784ef6cb62bb1e22d4e3613d99857833b86919be")
  (scope (tracked-changes 33))
  (submodule-updates
    (orgs/octave-commons (daimoi eros-eris-field eros-eris-field-app eta-mu-sol fork_tales gates-of-aker lineara_conversation_export promethean promethean-agent-system shibboleth simulacron))
    (orgs/open-hax (axxium commanoxx depenoxx eta-mu openplanner privaxxy proxx uxx vexx))
    (orgs/agustif (codex-linux))
    (orgs/riatzukiza (TANF-app))
    (orgs/shuv (mcporter)))
  (file-changes
    (removed (.factory/skills/submodule-ops/SKILL.md AGENTS.md README.md bin/align-submodules bin/fork-tax-submodules))
    (modified (.ημ/PRINCIPLE.edn services/openplanner/compose/proxx.yml services/proxx/policies/runtime/00-manifest.edn services/proxx/policies/runtime/10-model-families.edn spec.json)))
  (excluded-secrets
    (passwords.csv services/proxx/cephalon-hive-accounts.json services/proxx/cephalon-hive-providers.json services/proxx/proxx-federation-accounts.json services/proxx/proxx-federation-providers.json services/openplanner/scripts/sync-runtime-secrets-env.sh services/openplanner/scripts/unfragile-mongo-reset.sh))
  (concurrent-dirt
    (untracked-count 1478)
    (categories
      (audio (mp3 wav) ~452)
      (lore (fork-tales events world-states plot-logs world-building) ~200+)
      (graphics (svg seals emblems) ~100+)
      (kanban (drafts service-scaffolds) ~100+)
      (music (ustx) ~2)
      (services (eta-mu/kanban-cljs eta-mu/kanban) ~2)
      (orgs (lyrical-engine markov_song_engine) ~2))
    (note "All untracked files left as documented residual per fork-tax guardrails. Secrets explicitly excluded.")))
