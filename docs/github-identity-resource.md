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
- **Tree:** `did:web:<host>:actors:err` is the root. Grok Bot, PR Review Bot and
  each repo bot are its children. Any bot can have children of its own.

```
err ─┬─ grok-bot        credential github-app (grokbot-foresight, imported)
     ├─ pr-review-bot   credential github-app (own App)
     └─ clio-bot ── clio-helper
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
- **G4:** a token is minted only for scopes that fit inside the holder's policy.
  The holder itself or a managing ancestor can mint.
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

Minting works like this. Axxium signs an app JWT locally (RS256, 9-minute
lifetime). It then calls `POST /app/installations/:id/access_tokens`, narrowed to
the requested repos and permissions. Tokens are cached in memory until 60 seconds
before they expire. The cache key is (credential, generation, scope), so a
rotation invalidates the cache.

`token_for` also returns `:axxium/marker`, which is
`<!-- axxium-actor:v1 did=… -->`, for attribution in comments. The function
`law/action-allowed?` encodes the forbid rules `:approve-own` and `:merge-own`.

## Importing grokbot-foresight

To make the existing app Grok Bot's credential, the key stays where it is.

1. Set `AXXIUM_GITHUB_IDENTITY_KEY_DIR` to the directory that holds the PEM.
2. Call `credential_provision` with `{:actor/did <grok> :credential/label
   "github-app" :credential/key-file <path> :github/app-id 5572746
   :github/app-slug "grokbot-foresight" :github/installation-id 177661475}`.

Axxium only reads key files under that directory, resolved with `realpath`.

## MCP (`POST /api/github/mcp`, `{tool, arguments: EDN}`)

The tools are `actor_list`, `actor_provision`, `actor_suspend`, `actor_resume`,
`actor_revoke`, `credential_provision`, `credential_rotate`, `credential_revoke`
and `token_for`.

## Config

| env | meaning |
| --- | --- |
| `AXXIUM_GITHUB_IDENTITY_KEY_FILE` | path to a 32-byte base64 sealing key |
| `AXXIUM_GITHUB_IDENTITY_LEDGER_FILE` | the append-only EDN ledger |
| `AXXIUM_GITHUB_IDENTITY_KEY_DIR` | the only directory imported PEMs can be read from |

## Not yet

- The ledger supports a single writer process only.
- There is no proxied review/comment/merge yet. Callers use the minted token and
  apply `action-allowed?` and the marker themselves.
- PATs and SSH keys can be stored, but nothing mints with them yet.
- Nothing has been run against real GitHub.

## Host agents (yoga, stealth)

The seed `resources/identity/foresight-tree.edn` holds no secrets. It lists actor
paths, which `resolve-seed` turns into `did:web:<host>:actors:<path>`. The
seeded tree looks like this:

```
err (:human)
├─ err:grokbots (:group) ─ grok-bot, pr-review-bot, <repo>-bot ×25 (:bot)
├─ err:hosts:yoga (:host)    ─ opencode, codex, claude-code (:agent)
└─ err:hosts:stealth (:host) ─ opencode, codex, claude-code (:agent)
```

Host agents get the shared fork policy: write access to the 25 forks, with
`:approve-own`, `:merge-own` and `:force-push` forbidden. Each repo bot is
limited to its own repo. Suspending a host suspends its agents (G3).

### Credential kinds

| kind | what is stored | typical use |
| --- | --- | --- |
| `:github-app` | sealed PEM, or a key-file path; plus app and installation ids | `token_for` |
| `:api-key` | sealed secret, plus `:credential/provider` and `:credential/env` | model provider keys |
| `:ssh-key` | sealed private key | git over SSH |
| `:github-pat` | sealed token | a real user account, later |
| `:bootstrap-key` | only its SHA-256 digest | the agent's device credential |

### Bootstrap: an agent authenticates as itself

1. A managing ancestor (Err or the host) calls `bootstrap_issue {:actor/did
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
`:api-key` under its `:credential/env`. It then calls `token_for` for a short-lived
`GH_TOKEN`, and execs the tool.

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
