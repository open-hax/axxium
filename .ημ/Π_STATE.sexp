(fork-tax-state
  (repo "devel")
  (branch "pi/fork-tax/2026-04-15-170411")
  (base "b7d2b950")
  (timestamp "2026-05-16T04:00:00Z")
  (submodules
    (openplanner "a25ff73f")
    (knoxx "094273c3"))
  (scope "openplanner-proxx-compose-stability kafka-profile submodule-pointer receipts")
  (verification
    "services/openplanner compose kafka+kafka-replay config passed"
    "openplanner clojure worker image build passed"
    "audit heartbeat observed"
    "dry-run replay processed 5 messages"
    "bounded non-dry-run replay [0,1) idempotent"
    "health and metrics Kafka checks passed")
  (residual
    "pre-existing staged .gitmodules"
    "unstaged AGENTS.md"
    "pre-existing staged Blaze/Graphics/Lore creative artifacts and reorganizations"
    "other non-target root dirt intentionally left untouched"))
