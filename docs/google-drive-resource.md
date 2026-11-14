# Google Drive resource

Any Axxium user signs in with Google and links **their own** Drive. Axxium
holds a sealed refresh token per identity and exposes put/get/list through a
Drive-specific EDN RPC that reuses MCP tool names. `/api/drive/mcp` is not a
standard MCP JSON-RPC endpoint: it does not implement `initialize`, `tools/list`
or `tools/call`. OpenPlanner and agents address Drive files by **resource id**.

## Model

Objects (all are `axxium.domain.resource` resources, registered through
`policy/act`, so every registration has its decision recorded first — law 7):

| kind | id | store | location |
|---|---|---|---|
| `:resource/google-drive-credential` | `google-drive-credential:<owner-did>` | `:axxium` | `{:drive/sealed-refresh-token "v1.<iv>.<tag>.<ct>"}` |
| `:resource/google-drive-file` | `google-drive:<drive-file-id>` | `:google-drive` | `{:drive/file-id …}` |

File metadata: `{:drive/name :drive/mime-type "application/edn" :drive/parents [...] :drive/refs [<event-id> …]}`.
`:drive/refs` lists the clio/Mongo event ids the file archives, so
`drive_list {:drive/ref <event-id>}` finds every Drive file for an event.

Owner: a Google-signed-in actor is `did:web:<host>:actors:<actor-id>`
(`axxium.law.google-drive/actor-did`).

Laws:

1. Scope is exactly `drive.file` (files this app created); any wider grant is refused at callback.
2. Put never overwrites: a same-named untrashed file in the folder refuses with `:exists` (law 9).
3. No delete, trash, update or patch call exists in `axxium.extern.google-drive` (tested).
4. Refresh tokens are AES-256-GCM sealed with the owner DID as AAD; plaintext is never stored or logged.
5. `get ∘ put = id` for every EDN value (values stored as `pr-str`, read with `clio.shape.edn/read-one`). Embedding float vectors are bit-identical (`Object.is`), except `-0.0`, which EDN prints as `0`.
6. Only the owner can get or list a file resource.

## Setup

1. Google Cloud project → OAuth client (Web). Add the redirect URI
   `<AXXIUM_PUBLIC_BASE_URL>/api/drive/callback`. Enable the Drive API.
   Consent screen scope: `https://www.googleapis.com/auth/drive.file`
   (non-sensitive; no Google verification needed for this scope alone).
2. Credentials, by either:
   - `GOOGLE_DRIVE_CLIENT_ID` + `GOOGLE_DRIVE_CLIENT_SECRET`, or
   - the existing sign-in client file `GOOGLE_OAUTH_CLIENT_FILE` (fallback).
3. Set `AXXIUM_DRIVE_TOKEN_KEY_FILE` to the path of a file holding 32 random
   bytes encoded as base64. Create it once:

   ```sh
   (umask 077 && set -C && : > drive-token.key && chmod 600 drive-token.key && openssl rand -base64 32 >> drive-token.key)
   ```

   Never commit the key. Losing it means every user reconnects.
4. `AXXIUM_DRIVE_LEDGER_FILE`: path of the append-only EDN ledger
   (`axxium.extern.ledger-file`) holding the resource events.

Unconfigured, the routes answer 503.

## Routes

| | |
|---|---|
| `GET /api/drive/connect` | session required; signed 5-minute state cookie (actor-bound) + PKCE; redirects to Google with `access_type=offline prompt=consent` |
| `GET /api/drive/callback` | session must match the state's actor; exchanges the code, checks the scope, seals and registers the credential |
| `POST /api/drive/mcp` | Drive-specific EDN RPC, session required; body `{"tool": "drive_put", "arguments": "<EDN map>"}`; reply `{"edn": "<EDN>"}`; not standard MCP JSON-RPC |

## Drive RPC tool names (`axxium.extern.drive-mcp/call`)

```clojure
(call deps {:owner did} "drive_put"  {:drive/name "x.edn" :drive/folder-id "F"
                                      :drive/refs ["<event-id>"] :drive/value any-edn})
;; => {:ledger lg' :result <file resource>}  | {:error :exists|:not-connected|:invalid ...}
(call deps {:owner did} "drive_get"  {:resource/id "google-drive:<id>"})
;; => {:result {:resource/id … :resource/metadata … :drive/value …}} | {:error :not-found}
(call deps {:owner did} "drive_list" {:drive/ref "<event-id>"})   ; ref optional
;; => {:result {:resources [...]}}
```

The caller persists `:ledger`; the HTTP route serializes calls in-process
and appends each batch as one fsynced write.

Every Google request has a 15-second timeout, including OAuth exchange and
refresh. Caller cancellation is preserved.

Reconciliation: a same-name file already registered to this owner returns
`:exists`. A matching Google file absent from the owner's registry returns
`:exists-untracked` with `:drive/existing` file ids. A create followed by refused
registration returns `:created-untracked`, `:drive/reason :forbidden` and its
file id. A local append failure returns no success; retrying then exposes the
untracked file. These states require explicit reconciliation: names alone do
not authorize adoption, replacement or a duplicate upload.

## Open

- Ledger writes are single-process; multi-instance needs a shared store.
- Recovery repairs incomplete final lines, but does not prove multi-event
  batch atomicity after a crash.
- Listing reads Axxium's registry, not Drive; files created outside Axxium are invisible (by design under `drive.file`).
- Large blobs use multipart upload (≤5 MB); resumable upload is a follow-up.
- Clio EDN↔JSON tagged mapping (clio#7) is not used; the HTTP body carries EDN text.

## Separate Drive access for managed actors

`AXXIUM_DRIVE_ACTOR_ACCESS_ENABLED=true` enables these routes when a Drive
client, sealing key and ledger and an identity ledger and root operator are
configured. It defaults to off.

From the configured human owner's signed-in Axxium browser, send a same-origin
`POST /api/drive/actor/connect` with JSON `{"actor-did":"<exact managed actor DID>"}`.
Navigate to the returned `authorization-url` to grant that one actor access.
Google uses the same registered `/api/drive/callback` as human Drive consent.
The signed enrollment state pins the selected actor, the owner's exact session,
and the configured root. Axxium rechecks them before recording the owner's
approval and sealing the refresh token to the actor.

A Codex, Claude or OpenCode process authenticates independently as a managed
agent or bot. Send the agent's `x-axxium-actor` DID header and bootstrap bearer
to `POST /api/drive/agent`, using the same JSON/EDN envelope as the human RPC.
The available tools are `drive_list`, `drive_get` and `drive_put`. A client
adapter is required for standard MCP discovery. The caller cannot name another owner, use
the human's token or read another actor's registered resource. Authorization is
checked again before a result is returned, so key rotation or suspension during
a Drive read suppresses that result.

Consent starts the actor with an empty registry. `drive_put` writes a new EDN
file in the consented My Drive root and registers it under that actor:

```clojure
{:drive/name "work-notes.edn"
 :drive/idempotency-key "work-notes-2026-11-14"
 :drive/refs ["<event-id>"]
 :drive/value {:status "draft"}}
```

The idempotency key is 1–128 ASCII letters, digits, periods, underscores or
hyphens and belongs to the actor. The EDN value can be up to 1 MiB. An actor
can create files and read its registered files. Folder selection, import,
overwrite and delete are outside this API.

Before upload, Axxium records the allowing decision and the reserved Google
file ID. Repeating the exact request returns the existing receipt or looks up
that exact reserved ID and verifies its name, media type and bytes. A repeat
with different args returns `:idempotency-conflict`. A same-name unrelated
file yields `:exists-untracked`, without exposing another actor's file ID. On
`:reconciliation-required`, retry only with the same key and identical args.
A replaced Google grant cannot continue a pending upload from the prior grant.
Google can hold duplicate names from writes outside this route. The reserved
ID prevents creating a second file for a retried Axxium request.

[Google Drive API guidance on pre-generated IDs](https://developers.google.com/workspace/drive/api/guides/manage-uploads#use_a_pre-generated_id_to_upload_files)
