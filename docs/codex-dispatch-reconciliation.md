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
disposition.

## Linux peer-authenticated reader

`axxium.extern.codex-peer-reader/capture!` accepts the built Node-API addon
and an independently approved binding. The configuration carries
`:socket-path`, `:socket-device`, `:socket-inode`, `:uid`, `:gid`,
`:pid`, `:exec-sha256`, and the exact actor/device/thread/generation
`:binding`. Device and inode values are decimal strings. The operator
establishes these pins before using the reader. Pin the canonical socket
vnode. If Codex publishes a symlink as its control endpoint, verify its
target independently and supply that target path.

The Linux guard connects to the pinned Unix socket, checks the connected
peer's credentials and process descriptor, and retains its executable file.
The nbb reader verifies the expected executable digest and performs the
WebSocket exchange over the same verified connection. It rechecks the
peer and executable at collection close. The connection is single-use and
requires kernel support for `SO_PEERPIDFD`. [Node connected socket API](https://nodejs.org/docs/latest-v22.x/api/net.html#new-netsocketoptions)

The reader sends `initialize`, `initialized`, and the two bounded read
methods described above. It requests one initial queue page with a limit
of 100 and preserves the response's continuation for reconciliation. It
reads full-turn history in pages of up to 50 entries. It returns the
configured `:approved-reader` and paired `:evidence` for reconciliation.
Keep this capture within the client and pass it to the normalizer for the
exact saved intent. The transport
uses a single 20-second collection budget, with up to five seconds each for
connect and upgrade. The reader limits inbound socket bytes to 8 MiB,
individual WebSocket messages to 2 MiB, and message and control events to
256 per capture. Page and item limits also apply.

Build the guard with Linux C and Node-API headers, then run the isolated
socket fixture with Node and nbb installed locally.

```sh
npm run test:codex-peer
```

The build writes `target/codex-peer/codex_peer.node`.
`AXXIUM_NODE_INCLUDE_DIR` selects the directory containing `node_api.h`.
The regular server start path is separate from the reader. Enrolling the
actual Desktop endpoint requires its own runtime qualification.

## Reconcile an attempt after an inbox hint

Create one `axxium.extern.codex-dispatch-client/inbox-intake` worker for the
saved caller ID, private store, and independently pinned Desktop peer.
Pass the worker the exact binding verified by the authenticated Axxium
subscription adapter and the decoded string-keyed
`notifications/resources/updated` frame for `axxium://mailbox/inbox`. Use the
binding from the authenticated subscription and local configuration. The
frame itself carries only a resource URI.

Each accepted hint triggers an authenticated, bounded queue and full-turn
read for the saved attempt. Hints received before the first read coalesce.
Multiple hints during a read retain one pending read for the same intent.
The worker uses one connection at a time. An ordinary read returning no
matching evidence leaves the saved attempt recoverable. A terminal
checkpoint satisfies the original intent. Recovery validates the saved
binding and syncs the pinned directory before returning terminal success.
A directory sync failure leaves the checkpoint available for another
recovery attempt. The worker requires a separate saved attempt for later
mailbox work.

Codex sends native turn and queue notifications to connections associated
with the thread. A fresh initialized reader lacks that association. The
intake worker uses Axxium's existing authenticated hint instead of waiting
on the Desktop socket. Each Desktop connection runs initialization and
bounded reads. The worker can only checkpoint a verified matching turn.
Host receiver wiring and live endpoint enrollment require a runtime test.

## Terminal checkpoint and rollover

Use `axxium.extern.codex-dispatch-client/retire-capture!` to apply an
authenticated capture to a saved attempt. Only a unique matching completed
turn can retire the intent. The caller can also supply the digest of a
separately approved new notice. The store commits the terminal turn and item
and the optional successor reservation together. Each parent reserves at
most one successor. Retries recover the same reservation, while a different
witness or notice digest conflicts. A late queue acceptance preserves the
terminal state and the reservation.

After a process restart, use `prepare-successor!` with the original caller ID
and the current authorized binding. The store syncs the parent checkpoint
before it materializes the successor. That child has a reserved, deterministic
caller ID scoped to the exact predecessor. It uses the existing
`begin-attempt!` operation for its own one-time permit. A checkpoint without
a successor returns no prepared intent. The original intent remains retired.
The child's prepared record links to its predecessor. A legacy record at
the same caller ID is still recoverable in its original role and conflicts
with successor materialization. Before writing a parent checkpoint or a
child preparation, the store checks all saved phases at the reserved child
ID. A malformed or orphaned phase refuses the operation before either
record is added.

The checkpoint preserves the IDs and digest. The caller retains the approved
notice content and supplies its digest. Authorization of new hints,
automatic hint coalescing, native submission, mailbox acknowledgment and
completion of the assigned work have separate contracts.

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
