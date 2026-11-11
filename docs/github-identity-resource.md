# Bot identities and their GitHub credentials

Axxium manages one identity per bot. Each identity is a separate actor in a tree
rooted at a human. Every actor owns its own credentials.

## Model

- **Objects**
  - Actor node: a `:resource/bot-actor` with id `bot-actor:<did>`. It stores
    `{:actor/parent :actor/policy :actor/status}`.
  - Credential: a `:resource/credential` with id `credential:<did>:<label>`.
    It is owned by exactly one actor and has a `:credential/type`, one of
    `:github-app`, `:github-pat` or `:ssh-key`.
- **Morphisms:** register-root, provision (actor or credential), set-policy,
  suspend, resume, revoke, rotate and mint.
- **Tree:** a human root (e.g. `did:web:<host>:actors:alice`) with groups,
  hosts, agents and bots below it. Any actor can have children of its own.

```
alice ─┬─ assistant-bot   credential github-app (existing App, imported)
       ├─ review-bot      credential github-app (own App via manifest)
       └─ widgets-bot ── widgets-helper
```

## Laws

- **G1:** a child's policy is a subset of its parent's. Repos and permission
  levels can only shrink. Actions can only shrink. Forbids can only grow.
- **G2:** only a strict ancestor that is itself effective can provision,
  suspend, resume or revoke an actor or its credentials. Siblings cannot act on
  each other, and an actor cannot lift its own suspension.
- **G3:** an actor is effective only when it and all of its ancestors are
  `:active`, so suspending an actor also suspends everything under it. Children
  are not rewritten, so resuming the parent restores the whole subtree.
- **G4:** the holder or a managing ancestor can request a read-only GitHub
  installation token within the holder's repository and permission policy.
- **G5:** secrets are sealed with AES-256-GCM and bound to the owner's DID as
  AAD. An imported key file is referenced by path. Plaintext never enters the
  ledger. Revoking a credential drops the secret from the current resource.

Every operation records its policy decision before its effect (law 7). If an
operation is structurally invalid, the decision is recorded with no effect, so a
generic authority grant cannot bypass G1 or G2. The ledger is append-only EDN.

## GitHub Apps, one per bot

GitHub blocks automated sign-ups, so each bot is its own GitHub App. It acts as
`<slug>[bot]`. The flow:

1. The parent calls `POST /api/github/manifest {did}`. Axxium returns a form that
   posts a manifest built from the child's policy: a private app, webhooks off,
   and only the permissions in that policy. The state is a signed 5-minute cookie
   bound to both the parent and the child.
2. GitHub redirects to `/api/github/manifest/callback?code=…`. Axxium exchanges
   the code at `POST /app-manifests/{code}/conversions`, seals the PEM to the
   child's DID and registers the credential. It then redirects the parent to the
   app's install page.
3. After installing, call `credential_rotate` with `:github/installation-id`.

To export a raw installation token, the holder's policy must allow `:read`.
Pass concrete repository names and a nonempty permission map. Supported GitHub
permissions at level `read` are `checks`, `contents`, `issues`, `metadata`,
`pull_requests`, and `statuses`. GitHub adds its required metadata read
permission. Axxium refuses write, empty, and unrecognized permission requests
before contacting GitHub.

Axxium signs an app JWT locally (RS256, 9-minute lifetime) and verifies the
GitHub installation's account and current permission grants before requesting a
token. It uses an internal metadata-read token limited to the requested names
to verify their full names, owner, and immutable repository IDs. The final token
request uses those IDs, and Axxium checks the returned scope before serving the
token. This flow supports user and organization installations.

The cache key includes the credential generation, verified installation and
repository IDs, and requested permissions. Axxium rechecks the scope before
serving a cached token and stops reusing it 60 seconds before expiration.

GitHub permission levels group multiple actions together. For example,
`pull_requests:write` grants review submission with any supported review event.
The identity policy retains narrower actions and forbids for managed operations.
`token_for` only exports reads. GitHub repository rules independently govern
branch protection, bypasses, and force pushes.

`token_for` also returns `:axxium/marker` (`<!-- axxium-actor:v1 did=… -->`) to
identify the actor for consumers that record attribution.

## Importing an existing GitHub App

To make an existing App an actor's credential, the key stays where it is.

1. Set `AXXIUM_GITHUB_IDENTITY_KEY_DIR` to the directory that holds the PEM.
2. Call `credential_provision` with `{:actor/did <did> :credential/label
   "github-app" :credential/key-file <path> :github/app-id <id>
   :github/app-slug <slug> :github/installation-id <installation id>}`.

Axxium only reads key files under that directory, resolved with `realpath`.

## MCP (`POST /api/github/mcp`, `{tool, arguments: EDN}`)

The tools are `actor_list`, `actor_provision`, `actor_suspend`, `actor_resume`,
`actor_revoke`, `credential_provision`, `credential_rotate`, `credential_revoke`
`token_for`, `bootstrap_issue`, `credentials_self`, `subtree_export` and
`subtree_import`.

## Config

| env | meaning |
| --- | --- |
| `AXXIUM_GITHUB_IDENTITY_KEY_FILE` | path to a 32-byte base64 sealing key |
| `AXXIUM_GITHUB_IDENTITY_LEDGER_FILE` | the append-only EDN ledger |
| `AXXIUM_GITHUB_IDENTITY_KEY_DIR` | the only directory imported PEMs can be read from |
| `AXXIUM_IDENTITY_SEED_FILE` | optional secret-free EDN tree applied idempotently at startup |
| `AXXIUM_IDENTITY_ROOT_OPERATOR_ACTOR_ID` | optional exact Axxium account ID (`actor_<UUID>`) of the human operator |
| `AXXIUM_IDENTITY_ROOT_DID` | full resolved DID of the seed root, required with the operator ID |

Set the two root-operator values together in the deployment's host-local
configuration. The binding uses the operator's live human session and requires
an active human root already admitted to the identity ledger. The configured
root is reserved to that operator even when another account's default DID
matches it. Other callers retain their usual account DIDs. Bootstrap agent
credentials retain their own DIDs and cannot act as the configured root.

## Not yet

- The ledger supports a single writer process only.
- A write proxy for review, comment, push, and merge is still pending.
- PATs and SSH keys can be stored, but nothing mints with them yet.
- Nothing has been run against real GitHub.

## Seed files

A deployment can describe its tree in a secret-free EDN resource file and
point `AXXIUM_IDENTITY_SEED_FILE` at it. See
[`resources/identity/example-tree.edn`](../resources/identity/example-tree.edn):

```
alice (:human)
├─ alice:bots (:group) ─ review-bot (:bot)
└─ alice:laptop (:host) ─ coding-agent (:agent)
```

- `:policies` names reusable policies; a node's `:policy` is a map or one of
  those names.
- IDs are actor paths, resolved to `did:web:<axxium host>:actors:<path>`, or
  full DIDs. For a new tree on `axxium.example:18874`, the DID authority is
  `axxium.example%3A18874`. Use the resolved root DID in the owner binding.
- An existing actor retains its recorded DID. New seed descendants under a
  legacy parent use that parent's namespace. If a relative path matches
  existing actors under both spellings, Axxium refuses the seed before
  appending events. Explicit full DIDs retain their spelling.
- At startup the root registers itself if absent, then each node is created
  through the normal provision op as its parent (G1 applies). Existing actors
  are left untouched, so restarts are no-ops; nothing is ever deleted or
  changed by a seed. Credentials are never in seeds; add them in the portal.

Host agents (`:host` → `:agent`) let each coding tool on a machine hold its
own keys. Suspending a host suspends its agents (G3).

## Admin interface (portal)

`/portal/index.html` shows `<axxium-identity-manager>` under the account
manager for any signed-in actor that has an identity node. It talks only to
`POST /api/identity/admin` (`{op, ...}` JSON, contract in
`axxium.law.identity-admin`), which runs the same policy-checked tools and
ledger as MCP. The signed-in actor sees itself and its descendants and can:

- create child actors (kind, slug, name; policy prefilled from its own and
  only narrowable, G1);
- suspend, resume or revoke a descendant (G2, G3);
- provision, rotate or revoke credentials. Secret fields are write-only:
  they are cleared on submit, sealed server-side (G5), and no response ever
  contains them;
- issue a bootstrap key, displayed once with copy/dismiss;
- start the GitHub App manifest flow for a descendant;
- export its subtree as EDN (actors and policies, no credentials) and import
  such EDN idempotently (`created` / `unchanged` / `refused`).

### Credential kinds

| kind | what is stored | typical use |
| --- | --- | --- |
| `:github-app` | sealed PEM, or a key-file path; plus app and installation ids | `token_for` |
| `:api-key` | sealed secret, plus `:credential/provider` and `:credential/env` | model provider keys |
| `:ssh-key` | sealed private key | git over SSH |
| `:github-pat` | sealed token | a real user account, later |
| `:bootstrap-key` | only its SHA-256 digest | the agent's device credential |

### Bootstrap: an agent authenticates as itself

1. A managing ancestor (the root or the host) calls `bootstrap_issue {:actor/did
   <agent>}`. The key (`axb_…`) is returned once and only its digest is stored.
   Calling `bootstrap_issue` again rotates the key, and the old one stops
   working right away.
2. The key is placed on the host, for example in a mode 0600 file for that
   agent. Nothing else from the parent goes on the host.
3. The agent calls `POST /api/identity/agent` with the headers
   `x-axxium-actor: <its did>` and `authorization: Bearer <key>`. Only
   `credentials_self`, `token_for` and `actor_list` are open on that route, and
   every call runs as the agent itself. The agent only ever sees its own
   credentials. The key stops working if the agent or any ancestor is suspended
   or revoked.

### How each tool would consume its credentials (wrapper sketch, not shipped)

A small launcher on the host calls `credentials_self` and exports each
`:api-key` under its `:credential/env`. It then calls `token_for` for a
short-lived read-only `GH_TOKEN`, and execs the tool.

- **Codex CLI:** gets `OPENAI_API_KEY` and `GH_TOKEN` from the environment. The
  launcher writes nothing to `~/.codex`.
- **Claude Code:** gets `ANTHROPIC_API_KEY` and `GH_TOKEN` from the environment.
- **OpenCode:** gets the provider keys it is configured for (for example
  `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` or `OPENROUTER_API_KEY`) as env vars,
  referenced from `opencode.json` as `{env:VAR}`, plus `GH_TOKEN`.
- **git over SSH:** the `:ssh-key` is loaded into a per-process `ssh-agent`
  (`ssh-add -`) and is never written to disk.

GitHub tokens expire within an hour, and the launcher mints a new one on each
run.
