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
The polling pilot limits the file to 2 MiB. A load above that limit or an
append that would exceed it fails without deleting stored events. Preserve the
ledger and pause pilot traffic if the limit is reached.

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
subscribed ChatGPT delivery remain separate from the polling profile.

## Opt-in webhook pilot

Set `AXXIUM_MAILBOX_WEBHOOK_ACTOR_DID` to one dedicated test recipient and
`AXXIUM_MAILBOX_WEBHOOK_KEY_FILE` to a separate 32-byte base64 key in a regular
mode-0600 file owned by the service user. The existing mailbox ledger must
also be configured. Only the selected actor's live AT Protocol session
advertises the event capability. Register that actor's mailbox before
requesting the event catalog or subscribing.

For a private two-actor pilot, set `AXXIUM_MAILBOX_PILOT_ALLOWED_DIDS` to the
recipient DID and the native sender DID, separated by a comma. The recipient
must equal `AXXIUM_MAILBOX_WEBHOOK_ACTOR_DID`. Both actors need a live verified
AT Protocol binding. The recipient still has to grant the sender permission
to send. An authenticated DID outside the pair receives 403 before the route
can open a mailbox session or access the ledger. Leave the allowlist unset to
retain the existing mailbox authentication policy.

The authenticated MCP endpoint supports `events/list`, `events/subscribe`
and `events/unsubscribe` for `message.available`. The recipient is derived
from the session. The event carries only `message_id`, and the subscription
covers messages accepted after activation. One verified HTTPS callback per
recipient is supported. A subscription expires within an hour, and refresh
with a replacement key requires a new signed challenge.

The single mailbox writer persists the sealed callback and access binding,
then records each attempt before HTTPS. Each delivery rechecks the live AT
Protocol session and mailbox read grant. Bounded retry keeps the event ID
stable, and HTTP acceptance records notification delivery separately from
mailbox acknowledgment and execution. While the pilot is configured, ordinary
appends leave 32 KiB of the 2 MiB ledger for settlement of the in-flight
attempt. After unsubscribe, an already-issued HTTPS request may complete.

### Subscriber OAuth connection

The pilot accepts the mailbox's existing AT Protocol session credentials.
For a private ChatGPT connection, set `mailbox-client-id` and
`mailbox-resource` in the existing private OIDC authority file. The client
must be a registered confidential authorization-code client with one exact
redirect URI from the ChatGPT connection settings. The resource is the
canonical public URL ending in `/api/mailbox/mcp`. Discovery advertises that
resource and the `mailbox:events` scope. Use authorization code with S256 PKCE
and the resource indicator at authorization and token exchange.

Consent requires the pilot recipient's live verified AT Protocol session.
The resulting grant binds the exact client, resource, actor, DID and parent
session. The scoped bearer can discover and manage events and use `whoami`.
Direct AT Protocol credentials continue to authorize the mailbox tools.
Provider revocation or loss of the parent session or DID binding stops future
delivery for that OAuth grant.

### Owner-scoped actor discovery and messaging

An operator can opt a mailbox deployment into owner-scoped routing with
`AXXIUM_MAILBOX_ACTOR_BINDINGS`. Set it to an EDN vector of pairs such as
`[["did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" "did:web:example.test:actors:owner:work"]]`.
Each pair binds a verified AT Protocol mailbox DID to an exact existing Axxium
actor DID. Both sides must be unique. Configure the mailbox ledger and the
identity tree ledger in the same Axxium instance. Keep the bindings in its
host-local configuration. Changes take effect on restart. Existing two-DID
pilots use their original configuration.

`discover_actors` lists the reachable actors in the authenticated caller’s
human-owned tree. It returns each actor’s DID, name, kind and `mailbox_did`.
Use `mailbox_did` as the `to` argument for `send_message`. A reachable actor
must be active through every ancestor and own a registered mailbox. The
directory follows recorded parent links, including when actors use older DID
spellings. A recipient’s explicit send revocation still takes effect.

`resolve_address` and `send_message` check the same relationship. The server
checks it again for retries before returning a prior receipt. The caller acts
as its own verified mailbox identity. This grants no sibling inbox access,
acknowledgment, credential access, or actor management.

For the registered Work client, opt in with `:mailbox-actor-messaging? true`
in the private OIDC authority configuration. Request `mailbox:actors`
alongside `mailbox:events` and complete fresh consent. Work can then call
`discover_actors`, `resolve_address`, and `send_message`. Existing event-only
grants retain their existing tools. The new grant is pinned to the actor DID
configured at consent. Changing that binding requires fresh consent for
actor messaging. Direct AT Protocol clients, including Codex, can use the
directory with their verified session and continue to poll their own inbox.
Bindings and mailbox registrations are provisioned separately. An actor
with no registered mailbox does not appear in discovery.

### Work consent from the Axxium owner account

On the canonical Axxium instance, opt in with `"mailbox-owner-consent?": true`
and `"mailbox-actor-messaging?": true` in the existing private OIDC authority
JSON. Use the canonical Axxium issuer and its `/api/mailbox/mcp` resource. Configure
`AXXIUM_IDENTITY_ROOT_OPERATOR_ACTOR_ID` and `AXXIUM_IDENTITY_ROOT_DID` for
the existing human account and its exact root. The selected Work mailbox DID
needs a current AT Protocol provider binding to its own active Axxium account
and an explicit mailbox-to-actor binding to an active child of that root.
Keep the existing actor DIDs, including older spellings.

The Work client requests `mailbox:events mailbox:actors` and the owner approves
through their existing Axxium browser session, including a session established
through Google. The human owner uses the existing login without an AT Protocol
account. Axxium binds mailbox access to the configured Work actor and mailbox
DID. Work access requires the same live owner session, provider binding,
active root-to-child relationship and granted scopes. Legacy event-only
grants keep their access and renewal through the existing AT Protocol consent
flow. Enabling owner consent ends access and renewal for older actor-messaging
grants. Fresh owner approval issues the replacement grant.
