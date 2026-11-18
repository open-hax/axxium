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
result of the assigned work have their own evidence. This law performs no
effects. Durable storage, atomic observation and write ownership, native
IPC, and live activation belong to the later client adapter qualification.

Run the same law fixtures under nbb or as part of Axxium's existing Shadow
test build.

```sh
nbb -cp src/cljs -e '(require (quote [axxium.law.codex-dispatch-test]) (quote [cljs.test :as t])) (t/run-tests (quote axxium.law.codex-dispatch-test))'
```
