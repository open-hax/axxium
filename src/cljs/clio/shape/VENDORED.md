# Vendored clio canonical EDN

`canonical.cljc` and `edn.cljc` are byte-for-byte copies of
`src/clio/shape/canonical.cljc` and `src/clio/shape/edn.cljc` from
open-hax/clio at commit `9b14cda67` (clio main, 2026-11).

Axxium builds with shadow-cljs Maven dependencies and cannot consume clio's
git coordinate yet, so the two pure namespaces are vendored unchanged. Do not
edit them here; re-vendor from a newer clio commit and update this note.
`axxium.law.clio-pin-test` pins the canonical form of a fixed event so a drift
between the two copies fails CI.
