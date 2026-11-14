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
