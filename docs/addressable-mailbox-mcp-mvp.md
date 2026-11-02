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
- Scope idempotency keys to the authenticated sender and mailbox; concurrent retries must resolve to one logical message and receipt. Compare the complete accepted payload and recipient when deciding whether a retry matches.
- Allow a registered worker to read and acknowledge a message. Provide cursor-based recovery.
- Keep unacknowledged messages recoverable across reconnects and cursor advancement; acknowledgment must apply only to the authorized recipient's message.
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
9. Concurrent identical sends with the same scoped key produce one logical message and receipt; a changed recipient or payload is rejected without another message.
10. Reading or advancing a cursor without acknowledgment does not lose an unread message, including after a crash or reconnect. Another actor cannot acknowledge it.

## Deployment gates
- Preserve the current production Axxium and Services topology.
- Build and test Axxium on Java 21 / Node 22; enforce zero new warnings and the JS extern boundary.
- Deploy first to isolated staging with synthetic actors; verify TLS, OAuth discovery, database persistence, denial cases, and end-to-end MCP roundtrip.
- Require independent review and passing repository checks before merging or production rollout.
- Never place real secrets in Git, AppDeploy source files, chat, or client-visible payloads.

## Current evidence
A GitHub feature branch has been created. No runtime code, deployment, end-to-end test, or production cutover is established by this specification.
