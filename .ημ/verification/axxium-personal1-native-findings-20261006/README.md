# Axxium personal PR1 native-findings verification

Reviewed source: `244f27766479ee2ca53595736c5ef66ca1d3d24d`; native CodeRabbit review `5429696925`. All five inline findings were reproduced against that source before implementation. The review body repeats those five findings without separate nitpick/outside-diff items.

## Red observation

`red-fixture.cljs` and `red-test.log` retain the baseline regression fixture and raw observed output: 17 tests / 59 assertions / 19 failures / 0 errors, with zero compiler warnings. Failures cover cookie seconds, all four unauthenticated agent endpoints, orphan entity retention, uncontrolled credential errors, conflicting first-login orphan rows, and concurrent callbacks. The compiler still exited zero despite assertion failures; assertion counts, not that exit code, determined red. The final fixture additionally adapts the client-release argument and selected actor ID to the transaction boundary and adds success/failure checks.

## Green observation

The complete compiled suite and emitted Node runner each report **22 tests / 78 assertions / 0 failures / 0 errors**. Typechecking and production build each report zero warnings. Whole-source clj-kondo reports zero errors/warnings; JS and deployment boundary checks and diff hygiene pass.

Tests invoke the actual route registration/handlers, HoneySQL façade, and PostgreSQL extern transaction adapter against in-memory query/client fixtures. They verify commit/rollback and client release, first-login uniqueness conflict recovery, two concurrent callback convergence, preservation of successful agent creation and credential responses, and discarding a client when rollback fails. PostgreSQL SQLSTATE `23505` plus the exact `idx_provider_bindings_subject` constraint qualify conflict recovery; unrelated errors propagate after rollback. No real database, provider callback, application service or deployment is exercised.

The transaction façade passes a transaction-bound HoneySQL function to callers; raw clients stay opaque outside the PostgreSQL extern. Both actor and agent routes use shared authentication/administrator wrappers: missing context yields 401; authenticated non-administrators yield 403. Unexpected guarded-route errors are logged and return controlled 500 responses. Cookie max-age now uses seconds. One existing redundant `let` in `extern/http.cljs` was flattened without semantic change to satisfy the whole-source zero-warning check.

## Isolation and remaining qualification

A new worktree from the exact reviewed personal head uses a private shallow clone. Ordinary descendant commits preserve all remote ancestry; no rewrite/force push is involved. The initial full-history fetch was stopped after transferring unrelated historical objects; its abandoned clone remains separate. npm dependencies, npm cache/config files, Java user directory and Maven repository are task-local. Compiler processes use `--force-spawn` and an ephemeral nREPL port; no shared worker, global cache/config, credential or service was mutated.

The PR remains draft and auto-merge off because the inherited ready-triggered workflow uses eager squash merging and inherited secrets. Source repair does not activate or alter that workflow. Fresh native exact-head review and hosted qualification remain required; historical approvals, skipped runs and quota responses supply no approval. No merge, board transition or live identity proof is claimed.

## Artifact hashes

- `green-test.log`: SHA-256 `c38ece6b3e32e2dea66392b75f224bd1fe6ac35f105b6f1ef1d1da847f05a9cf`
- `node-test.log`: SHA-256 `91122e349aeb000652a13b4d6448e8fc17968eb172b5c0412c53bbd0679c82a6`
- `production-build.log`: SHA-256 `66176d9deb3c3292a901a60ebf58929bad09bdf37d89ae26fe53070d68ca2dd6`
- `red-fixture.cljs`: SHA-256 `2beb470eb6b0cca58361baee0871da5990f258c36a5a4ec32a3c63b597733610`
- `red-test.log`: SHA-256 `577b0c3bb9bdaf81aa33997da5943ce2d1b85c103c368ddf8c5fbf53e647f5c0`
- `typecheck.log`: SHA-256 `a7eb0120a33d537c664553792f7f935f2c5d8c2a3b1eba4b5efd0e6c915e880d`

## Native summary docstring follow-up

The native summary comment `6017930818` also reports docstring coverage0% on one supported function, with38 async/other forms unsupported by that scan. Concise docstrings now describe affected public query/route adapters and repaired agent/first-login handlers. No runtime behavior changes in this follow-up. Its separate typecheck/build outputs report0warnings and both full test executions report22tests78assertions0failures0errors. The provider must recompute its own coverage; no percentage or warning waiver is claimed. Earlier raw artifacts remain unchanged.

- `docstring-typecheck.log`: SHA-256 `99188ee4cc6945921c7e4a31d9880f1246e8d17e8c0a4b19c60f71d157aaec82`
- `docstring-build.log`: SHA-256 `7a726d11d5d195d1a72b180e39ead1faeb54d04a027b76cc869791db0eb5c0d5`
- `docstring-tests.log`: SHA-256 `ec7a75b05c6ea142c4ec134fece6c6474655c9dfa23dec9d400f9d71036fcffd`
- `docstring-node-tests.log`: SHA-256 `91122e349aeb000652a13b4d6448e8fc17968eb172b5c0412c53bbd0679c82a6`
