# Addressable mailbox MCP — initial implementation contract

Status: proposed, not deployed. Parent: open-hax/axxium at c735a1e80dea28dc717e285918cf90189313d086.

## User story
As a user delegating work among ChatGPT sessions and external agents, I want an authenticated, durable, addressable mailbox so that a task can be resumed after interruptions without re-entering credentials or reconstructing its state.

## Boundaries
Axxium owns actor authorization, mailbox admission, session bindings, and receipts. The ATProto PDS remains authoritative for DID identity and is not a private mailbox. open-hax/services owns ingress, deployment and secrets. Existing proxx:relay credentials do not authorize mailbox operations.

## MVP
- Register a mailbox under an authenticated Axxium actor and issue a distinct, revocable mailbox grant.
- Provide MCP tools whoami, open_session, resolve_address, send_message, read_inbox, acknowledge_message and get_delivery.
- Persist message, idempotency key, owner, recipient and acceptance receipt atomically.
- Allow a registered worker to read and acknowledge a message. Provide cursor-based recovery.
- Publish message.available to explicitly subscribed recipients; use a durable outbox, not a connection as the source of truth.
- Keep credentials, cookies, callbacks and secrets out of message payloads and receipts.

## Acceptance tests (must fail before implementation)
1. Anonymous requests and cross-owner mailbox reads/writes are refused.
2. Repeated send with same key and identical body returns original message; changed body is rejected.
3. A crash after commit and before notification can be replayed without losing or duplicating the logical message.
4. A worker can acknowledge, reconnect, and resume from the last durable cursor.
5. Notification acceptance is not treated as execution completion.
6. Revoked grants, wrong audience, expired sessions, and forged sender IDs fail closed.
7. A live authenticated MCP client can discover tools, send a message, and read a receipt.
8. A real subscribed ChatGPT client can receive an event; unsupported clients use polling without a false push claim.

## Deployment gates
- Preserve the current production Axxium and Services topology.
- Build and test Axxium on Java 21 / Node 22; enforce zero new warnings and the JS extern boundary.
- Deploy first to isolated staging with synthetic actors; verify TLS, OAuth discovery, database persistence, denial cases, and end-to-end MCP roundtrip.
- Require independent review and passing repository checks before merging or production rollout.
- Never place real secrets in Git, AppDeploy source files, chat, or client-visible payloads.

## Current evidence
A GitHub feature branch has been created. No runtime code, deployment, end-to-end test, or production cutover is established by this specification.

## Opt-in HTTP polling candidate

Set `AXXIUM_MAILBOX_LEDGER_FILE` to a dedicated existing regular file owned by
the Axxium service user with mode `0600`. Run one writer, keep the file and its
directory separate from Drive and GitHub identity ledgers, and keep the
setting absent in deployments that do not operate a mailbox. The route
`/api/mailbox/mcp` uses the configured public origin and that URL as its
mailbox audience.

The polling ledger writes each operation as a single newline-terminated
`:axxium.mailbox/batch-v1` EDN frame. Recovery drops an incomplete final
frame and reads older complete event lines. Retrying registration completes
an interrupted legacy owner grant while preserving any recorded revocation.
Once a ledger receives framed writes, use a polling build that reads v1 frames.
The polling pilot limits the file to 2 MiB. A load above that limit or an
append that would exceed it fails without deleting stored events. Preserve the
ledger and pause pilot traffic if the limit is reached.

The polling profile uses MCP Streamable HTTP with the 2025-06-18 version
and JSON POST responses. Initialize with a live Axxium session issued by
the verified AT Protocol callback. A bearer works for programmatic callers.
A browser can use its session cookie on an exact same-origin request. The
server checks the active database session and current AT Protocol binding
before queued work. An ordinary account session or agent credential is
insufficient. Sessions signed before the callback began including its DID
require a fresh AT Protocol sign-in.

Echo the returned `Mcp-Session-Id` on subsequent requests. Use
`tools/list` and the mailbox tools to register mailboxes, authorize senders,
send, poll, and acknowledge. GET and DELETE return 405. The `open_session` tool reports the
current polling session. Only HTTP initialization opens a transport session. The HTTP profile does not advertise
MCP OAuth discovery or support SSE, cross-origin browser access, or ChatGPT
subscriber delivery. An exact Services staging image and actual subscriber
proof remain separate acceptance requirements.
