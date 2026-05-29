(fork-tax-state
  (repo "devel")
  (timestamp "20260529T022118Z")
  (scope "whole-root all-dirt snapshot flattened onto origin/main")
  (mode "user-requested soft reset onto main, then git add -A")
  (base "origin/main")
  (branches "full branch names recorded in Π_LAST.md")
  (tags "full tag names recorded in Π_LAST.md")
  (child-commits
    (knoxx "recorded in Π_LAST.md and manifest")
    (openplanner "recorded in Π_LAST.md and manifest")
    (eta-mu "recorded in Π_LAST.md and manifest")
    (openutau "local-only; push blocked; recorded in Π_LAST.md and manifest")
    (proxx "recorded in Π_LAST.md and manifest")
    (bitch-tracker "recorded in Π_LAST.md and manifest")
    (eta-mu-sol "local-only; no origin remote; recorded in Π_LAST.md and manifest"))
  (verification
    "child branches created by soft reset onto each default main/master"
    "knoxx/openplanner/eta-mu child branches and tags pushed"
    "openutau commit local-only because upstream https auth is unavailable"
    "root pre-commit secret hook timed out on the 14k-file staged tree"
    "targeted staged high-risk secret scan passed after provider-key fixture redaction"
    "full test suite skipped because all-dirt snapshot is intentionally mixed")
  (root-finalization
    (commit "commit containing this artifact")
    (tag "recorded in Π_LAST.md")
    (push "branch and tag pushed; final receipt amendment force-with-lease pushed"))
  (guardrail "this intentionally supersedes prior long-history root branch to avoid replaying secret-bearing history"))

(pi-state
  (timestamp "20260529T040216Z")
  (batch-manifest "/tmp/eta-mu-kanban-batches/agent_riatzukiza.json")
  (action "eta-mu kanban migration batch agent_riatzukiza")
  (root-tracked-paths ("orgs/riatzukiza/kronos" "orgs/riatzukiza/openplanner"))
  (submodules ("orgs/riatzukiza/ollama-benchmarks" "orgs/riatzukiza/openhax" "orgs/riatzukiza/riatzukiza.github.io"))
  (verification "migration script and eta-mu-beta kanban count spot checks")
  (concurrency "path-scoped staging only; unrelated dirt untouched"))

(pi-handoff
  (kind "eta-mu-kanban-migration")
  (batch "agent_shuv")
  (time "2026-05-29T04:04:21Z")
  (manifest "/tmp/eta-mu-kanban-batches/agent_shuv.json")
  (verification "migration script plus eta-mu-beta kanban count for every board")
  (submodules
    (entry (path "orgs/shuv/GitNexus") (commit "4d93b27") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/bridle") (commit "c99d40b") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/juno-code") (commit "3b321d6") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/kapture") (commit "85102dd") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/maple") (commit "46bfded") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/openplanner") (commit "2cf0eed") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/openshuvussy") (commit "4830b7a") (branch-push "blocked non-fast-forward") (tag-push "ok"))
    (entry (path "orgs/shuv/our-gpus") (commit "ca7d074") (branch-push "ok") (tag-push "ok"))
    (entry (path "orgs/shuv/proxx") (commit "75a3b26") (branch-push "blocked repository not found/access") (tag-push "blocked repository not found/access"))
    (entry (path "orgs/shuv/shuvcode") (commit "71c697ba2") (branch-push "blocked non-fast-forward") (tag-push "ok")))
  (concurrency "path-scoped staging only; unrelated dirt left unstaged; eta-mu paths not staged"))
