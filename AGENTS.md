# Axxium Agent Style Guide

# Architecture Paradigm: Categories vs. Contracts
When modeling domains, you must strictly differentiate between the grammar of motion and the enforcement of that motion.
- Categories: Describe the space of lawful possible transformations. They dictate "what kind of move this is" and define the state space, transition vocabulary, and general laws of composition for the runtime or a subsystem.
- Contracts: Decide whether a particular runtime entity, event, or transition is admissible under current obligations. They dictate "whether you are allowed to count it as a valid move right now" by defining guards, admissibility checks, evidence requirements, delivery expectations, and side-effect constraints.

# House Rules: The Constitution (`eta-mu-sol` style)
- Zero Warnings: Zero warnings in CI for `clj-kondo`, type checking, and tests. Warnings are failed contracts, not noise.
- Warning Ratchet: Do not allow warnings to accumulate. For every backend CLJS change, run `npm run typecheck`; fix any warning introduced or touched by the change before moving on. If an unrelated historical warning blocks verification, record it explicitly with the owning file and do not add to the baseline.
- Linter Enforcement: Turn on optional `clj-kondo` linters for `:missing-docstring`, `:unused-value`, `:shadowed-var`, `:used-underscored-binding`, `:warn-on-reflection`, `:unsorted-required-namespaces`, and `:refer`. Ban broad `:refer :all`.
- No Junk Drawers: Do not use `utils` namespaces.
- Architecture Split: Honor a strict four-category namespace architecture:
  1. `domain.*`: Pure business logic, typed data, domain-level decisions. No I/O.
  2. `infra.*` or `http/db/queue/*`: Effectful functor layer (transport, persistence). No domain policy.
  3. `shape.*`: Structure-only morphisms over data shapes. Must be pure and domain-agnostic.
  4. `law.*` or `contract/guard/*`: Contracts, validators (e.g., Malli), assertions. No I/O.
- Boundary Contracts: Every boundary-crossing function must name or call an explicit contract/schema validator (e.g., Malli) reflecting the Contract obligations of the relevant Category.
- Extern Boundary Layer: `axxium.extern.*` is the only place raw JavaScript interop should be born, decoded, encoded, sequenced, or mutated. Non-extern namespaces must not import generic JS interop helpers; those are implementation details for named extern adapters only.
  - Use boundary-specific adapters named for the system they own: `extern.pg`, `extern.bcrypt`, `extern.jose`, `extern.fastify`, etc.
  - Domain, law, shape, and ordinary infra code should receive and return CLJS maps/vectors/scalars. If a raw JS object must cross a layer, it must be treated as an opaque handle and only inspected by the owning extern adapter.
  - Do not hide boundary leaks with aliases. A call site using `js->clj`, `clj->js`, `js/JSON`, `Object.keys`, `array-seq`, `#js`, `aget`, `aset`, `js/Promise.all`, `FormData`, `Blob`, Fastify request/reply internals, or SDK-native objects is probably in the wrong namespace unless the file is an extern adapter or explicitly documented low-level wrapper.
- Custom Macros: Macros must expand to ordinary, lintable shapes. Register custom macros in `.clj-kondo/config.edn` using `:lint-as` on day one.

# Coding Directives & Clean Code Doctrine
- Optimize for the human reader's working memory: reveal intent through explicit naming, isolate responsibilities, and arrest entropy on contact.
- Continuous Truth (XP): Tighten the loop. Favor small, verifiable state changes over speculative architecture.
- Modern Asynchrony: ClojureScript 1.12.145+ supports native async/await. Always use the `^:async` metadata hint for functions and tests instead of legacy `core.async` channels, Promise chains, or shadow-cljs specific wrappers when targeting modern environments.
  - Functions: `(defn ^:async foo [n] ... (await (Promise/resolve ...)))`
  - Tests: `(deftest ^:async foo-test ... (await (foo ...)))`
- Clojure Idioms:
  - Use `when-let` instead of nesting `let` and `if` checks.
  - Strongly prefer threading macros (`->` and `->>`) over manual nested let forms to maintain linear readability.

## Data-Oriented Design

- Pass plain maps. Return plain maps.
- Tool execute functions receive a parameter map and return a result map.
- Avoid OO-style stateful tool builders.
- Composition happens in the orchestration layer by concatenating domain tool vectors.

## Verification Requirements

- Do not report a code change as done unless the relevant test command completes with zero failures and zero errors.
- For backend ClojureScript changes, run `npm run typecheck` and treat any reported test failure as blocking, even if the compiler exits 0.
- For production backend changes, also run `npm run build` or the narrower build command that proves the changed build target.
- If the full relevant suite is already red, either fix it before claiming completion or clearly state that the task is blocked by the failing tests; do not phrase a red suite as "verified" or "done."
- Only use a narrower test command when it directly covers the changed code and explain why the full relevant suite was not run.

## Modern CLJS Patterns

Always prefer modern shadow-cljs patterns over legacy verbose forms:

- Use `^:async` + `await` for async tests and top-level async functions (ClojureScript 1.12.145+)
- Use `when-let` instead of nesting `let` + `if` checks
- Prefer threading macros `->` and `->>` over manual nested let forms
- Use `some->` for optional chaining through potential nils
