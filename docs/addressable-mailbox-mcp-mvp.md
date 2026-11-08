# Addressable mailbox MCP — initial implementation contract

Proposed contract based on open-hax/axxium at c735a1e80dea28dc717e285918cf90189313d086.

## User story
As a user delegating work among ChatGPT sessions and external agents, I want an authenticated, durable, addressable mailbox so that a task can be resumed after interruptions without re-entering credentials or reconstructing its state.

## Boundaries
Axxium owns actor authorization, mailbox admission, session bindings, and receipts. The ATProto PDS remains authoritative for DID identity and is not a private mailbox. open-hax/services owns ingress, deployment and secrets. Existing proxx:relay credentials do not authorize mailbox operations.

## MVP
- Register a mailbox under an authenticated Axxium actor and issue a distinct, revocable mailbox grant.
- Provide MCP tools whoami, open_session, resolve_address, send_message, read_inbox, acknowledge_message and get_delivery.
- Persist message, idempotency key, owner, recipient and acceptance receipt atomically.
- Scope idempotency keys to the authenticated sender and mailbox. Concurrent retries must resolve to one logical message and receipt. Compare the complete accepted payload and recipient when deciding whether a retry matches.
- Allow a registered worker to read and acknowledge a message. Provide cursor-based recovery.
- Keep unacknowledged messages recoverable across reconnects and cursor advancement; acknowledgment must apply only to the authorized recipient's message.
- Publish message.available to explicitly subscribed recipients. Keep delivery state in the durable outbox.
- Keep credentials, cookies, callbacks and secrets out of message payloads and receipts.

## Acceptance tests (must fail before implementation)
1. Anonymous requests and cross-owner mailbox reads/writes are refused.
2. Repeated send with same key and identical body returns original message; changed body is rejected.
3. A crash after commit and before notification can be replayed without losing or duplicating the logical message.
4. A worker can acknowledge, reconnect, and resume from the last durable cursor.
5. Notification acceptance is not treated as execution completion.
6. Revoked grants, wrong audience, expired sessions, and forged sender IDs fail closed.
7. A live authenticated MCP client can discover tools, send a message, and read a receipt.
8. A real subscribed ChatGPT client can receive an event. Unsupported clients use polling without a false push claim.
9. Concurrent identical sends with the same scoped key produce one logical message and receipt. A changed recipient or payload is rejected without another message.
10. Reading or advancing a cursor without acknowledgment does not lose an unread message, including after a crash or reconnect. Another actor cannot acknowledge it.

## Deployment gates
- Preserve the current production Axxium and Services topology.
- Build and test Axxium on Java 21 / Node 22. Enforce zero new warnings and the JS extern boundary.
- Deploy first to isolated staging with synthetic actors. Verify TLS, OAuth discovery, database persistence, denial cases, and end-to-end MCP roundtrip.
- Require independent review and passing repository checks before merging or production rollout.
- Never place real secrets in Git, AppDeploy source files, chat, or client-visible payloads.


## Current evidence

The fork polling pilot passed hosted checks and a compiled loopback
roundtrip against disposable PostgreSQL with signed synthetic sessions. The
roundtrip exercised discovery, send, poll, restart recovery, acknowledgment,
and denial after session or provider-binding revocation. The composed source
at `97f91612d19432f1ae1dbf2ceeee304c06ca0d02` passed the full test suite,
typecheck, build, and the OIDC store and protocol suites. Services staging
activation and live client delivery remain open.

## Opt-in HTTP polling candidate

Set `AXXIUM_MAILBOX_LEDGER_FILE` to a dedicated existing regular file owned
by the Axxium service user with mode `0600`. Run one writer and keep the file
and its directory separate from the Drive and GitHub identity ledgers.
Leave the setting absent in deployments without a mailbox. The route
`/api/mailbox/mcp` uses its configured public URL as the mailbox audience.

The polling ledger writes each operation as a single newline-terminated
`:axxium.mailbox/batch-v1` EDN frame. Recovery drops an incomplete final
frame and reads older complete event lines. Retrying registration completes
an interrupted legacy owner grant while preserving recorded revocations.
Once a ledger receives framed writes, use a polling build that reads v1 frames.

The polling profile uses MCP Streamable HTTP version 2025-06-18 and JSON
POST responses. Initialize with a live Axxium session issued by the verified
AT Protocol callback. Programmatic callers can present it as a bearer.
A browser can use its session cookie on an exact same-origin request. The
server checks the active database session and current AT Protocol binding
before queued work. An ordinary account session or an agent credential
cannot satisfy this binding. Sessions issued before the callback recorded
the DID require fresh AT Protocol sign-in.

Echo the returned `Mcp-Session-Id` on subsequent requests. Use `tools/list`
and the mailbox tools to register mailboxes, authorize senders, send, poll,
and acknowledge. GET and DELETE return 405. The `open_session` tool reports
the current polling session. Only HTTP initialization opens a transport
session. Payloads require valid Unicode, finite numbers, and safe integers.
MCP OAuth discovery, SSE, cross-origin browser access, and actual
subscribed ChatGPT delivery remain separate from this polling pilot.
