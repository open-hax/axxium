# Reconcile a saved Codex Desktop dispatch intent

The Axxium nbb client needs to reconcile a saved notice after a lost queue
response or a restart. Caller IDs correlate observations. They do not make
native queue adds idempotent. In an isolated Codex Desktop 0.169.1 fixture,
submitting the same caller ID twice created two queue IDs. A loaded idle
conversation can start a turn before the native add returns. [Pinned native
queue implementation](https://github.com/openai/codex/blob/c376cf7396eff83ce4c6ac61b9553df142e7a0be/codex-rs/ext/queue/src/service.rs#L248-L290)

`axxium.law.codex-dispatch/reconcile` takes a previously saved intent with
`:binding`, `:caller-id`, `:notice-sha256`, and the optional native
`:queue-id` from an accepted add. The binding carries the approved actor DID,
device ID, exact Codex thread ID, and binding generation. These are opaque
existing identities. A display name, process ID, conversation title, or
mailbox notification does not establish ownership or binding.

Before projecting queue and history into the law, the caller must verify the
native peer, current binding, and complete coverage for the saved intent. The
caller generates a unique caller ID, persists it with a digest before
submission, and reconstructs the same digest from the native user input.
History projections contain only normalized, correlatable user-message items
with their turn and item IDs and turn status. A read with missing pages, stale
binding, or unverifiable correlation must not be marked complete.

| Native observation | Reconciliation |
| --- | --- |
| One matching queue row and no matching history item | Record its queue ID and retain the intent |
| One matching user item in a completed turn and no matching queue row | Retire the local notice intent |
| Missing, partial, mismatched, duplicated, or conflicting observations | Hold the original intent for investigation |
| In-progress, failed, or interrupted native turn | Hold the original intent |

Retirement describes the native notice turn. Mailbox acknowledgment and the
result of the assigned work have their own evidence. The reconciliation law
is pure.

## Linux client custody

Use `axxium.extern.codex-intent-store` with a pre-provisioned local
directory owned by the client account at mode `0700`. Keep that directory
stable across restart. The owner keeps authority over the queued notice and
its actor, device, thread, and binding generation. Use a unique caller ID
for each intentional submission and retain it when recovering an uncertain
outcome.

1. Build the intent with `axxium.extern.codex-dispatch-client/notice-sha256`
   and call `prepare!` before attempting a native submission.
2. Call `begin-attempt!` with the same intent. Only the caller that receives
   `:permit? true` may send. The attempt record is synchronized before the
   permit returns. A process that exits after receiving a permit leaves the
   same durable state as a lost response. A retry gets no new permit.
3. Use `record-accepted!` only with the verified queue ID for that exact
   attempt. `recover!` retains the accepted ID, and an empty native read
   leaves the saved intent available for investigation.

Records contain the exact binding, caller ID, notice digest, and optional
accepted queue ID. The store synchronizes each owner-private file and
publishes it with a create-only link and a directory sync. The owner must
preserve the checkpoint directory and its contents instead of restoring an
older copy or deleting an uncertain attempt.

## Native observations

Call `axxium.extern.codex-dispatch-client/reconcile-capture!` with the store,
caller ID, configured approved reader, and verified capture. The transport
establishes the reader's identity independently, checks the bound peer before
and after collection, pairs requests with replies, and applies byte and
deadline budgets. Capture validation checks the exact bound thread and
binding generation, the cursor chain, request/reply IDs, and the single text
of any matching notice. It accepts `thread/queue/list` and explicit full
`thread/turns/list` pages. A queue read must start at the first page and
return an explicit null continuation in that same response. The native
queue cursor is a live offset, so a queue read with a continuation holds the
saved intent. Full-turn history supports up to 16 pages and 4,096 items.
Each page allows up to 128 entries. The original
0.169.1 Unix server returned
method-not-found for `thread/items/list` in the isolated fixture.

The combined client reads the saved intent and returns a reconciliation
disposition. Applying that disposition, atomic retirement and rollover, peer
authentication for the installed Desktop endpoint, and live activation require
separate runtime qualification.

Run the focused fixtures under nbb or as part of Axxium's existing Shadow
test build. The filesystem fixtures use temporary Linux stores. The native
capture fixtures exercise complete and partial protocol evidence.

```sh
nbb -cp src/cljs -e '
(require (quote [axxium.extern.codex-dispatch-client-test])
         (quote [axxium.extern.codex-intent-store-test])
         (quote [axxium.law.codex-dispatch-test])
         (quote [axxium.law.codex-native-observation-test])
         (quote [cljs.test :as t]))
(t/run-tests (quote axxium.extern.codex-dispatch-client-test)
             (quote axxium.extern.codex-intent-store-test)
             (quote axxium.law.codex-dispatch-test)
             (quote axxium.law.codex-native-observation-test))'
```
