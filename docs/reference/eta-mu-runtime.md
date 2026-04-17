# Eta-mu runtime (Pi extensions) — shorthand + paths

When someone says **“eta-mu runtime”** in this `devel` workspace, they usually mean:

- the `@open-hax/eta-mu-extensions` package (CLJS) that builds Pi/OpenCode extensions
- plus its runtime state under `~/.ημ/state/…`
- plus the deployed compiled runtimes under `~/.pi/agent/extensions/cljs-*/`

This document exists so a human can say:

> “fix the eta-mu extension X”

…and an agent knows exactly which files and build steps that implies.

## Canonical locations (devel)

| What | Path |
|---|---|
| Source repo | `devel/orgs/open-hax/eta-mu/` |
| Extension package | `devel/orgs/open-hax/eta-mu/packages/eta-mu-extensions/` |
| Extension manifest (declares names + source paths) | `…/packages/eta-mu-extensions/manifest.edn` |
| Extension sources (CLJS) | `…/packages/eta-mu-extensions/src/eta_mu/extensions/*.cljs` |
| Canonical eta-mu runtime root (must exist) | `~/.ημ` (symlink → `…/packages/eta-mu-extensions/`) |
| Runtime state (per extension) | `~/.ημ/state/<extension>/` |
| **Devel build/cache target** (shorthand) | `devel/@.ημ/` |
| Devel build/cache/state contents | `devel/@.ημ/{.build,.shadow-cljs,state,shadow-cljs.edn}` |
| Pi deployed extension runtime | `~/.pi/agent/extensions/cljs-<extension>/{index.ts,runtime.js}` |
| Pi extension enablement list | `~/.pi/agent/settings.json` → `extensions` |
| OpenCode plugin outputs | `~/.config/opencode/plugins/<extension>.*` |

Devel convention:
- `devel/@.ημ/` is the **place we want generated artifacts** (build cache + compiled outputs + state) to live.
- The package directory symlinks its generated dirs (`.build`, `.shadow-cljs`, `state`, `shadow-cljs.edn`) into `devel/@.ημ/`.

## What “fix the eta-mu extension X” means

Given an extension name like `session-mycology`, `receipt-river`, or `opmf-contract-gate`:

1. Find the source file via the manifest:
   - open `…/packages/eta-mu-extensions/manifest.edn`
   - locate `{:name "X" … :path "…"}`
   - edit that CLJS file (usually under `src/eta_mu/extensions/`)
2. Rebuild and redeploy compiled runtimes:

```bash
pnpm -C devel/orgs/open-hax/eta-mu/packages/eta-mu-extensions run build
```

Optional clean first (use when outputs are suspicious/stale):

```bash
pnpm -C devel/orgs/open-hax/eta-mu/packages/eta-mu-extensions run clean
pnpm -C devel/orgs/open-hax/eta-mu/packages/eta-mu-extensions run build
```

3. Reload Pi so the new runtime is actually used (restart Pi, or `/reload` if supported).
4. Validate the deployed output exists at:
   - `~/.pi/agent/extensions/cljs-X/runtime.js`
5. If the bug is state-related, inspect:
   - `~/.ημ/state/X/`

### Legacy state fallback

Some extensions will fall back to legacy Pi state if `~/.ημ/state/<name>/` doesn’t exist:

- legacy state root: `~/.pi/agent/state/<name>/`

If behavior looks “haunted”, check both roots.

## Don’t edit generated outputs

- Do **not** hand-edit `~/.pi/agent/extensions/cljs-*/runtime.js` — it is generated.
- Do **not** commit `.build/` or `shadow-cljs.edn` from `eta-mu-extensions` — they’re generated.

## Common extensions

These are the usual suspects in Pi:

- `receipt-river`
- `session-mycology`
- `contract-runtime`
- `opmf-contract-gate` (output shape gate)
- `opencode-global-instructions`
