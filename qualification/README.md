# Axxium mutation evidence pilot

This producer binds each run to the clean, full Git HEAD of its own isolated
checkout. It is an experimental candidate, not reviewed or adopted
production-qualification machinery. The Services gate requires exact-revision
integration, E2E and four disjoint 250-kill batches. This pilot neither
constructs nor asserts those receipts; it always reports `qualified_batch: false`.
It currently requires Linux, a Git checkout with installed locked npm
dependencies, Python 3, Volta and Node 22.23.3.

```sh
python3 -m unittest discover -s qualification -p test_mutation_campaign.py -v
python3 qualification/mutation_campaign.py --inventory
python3 qualification/inspect_expanded_inventory.py
python3 qualification/mutation_campaign.py --limit 8 --out qualification/results/new-pilot
python3 qualification/mutation_campaign.py --path-prefix src/cljs/ --batch-index 0 --limit 1000 --out qualification/results/batch-0
```

Choose unused output directories under `qualification/results`; old evidence
must not be overwritten. Batch indexes partition candidate fingerprints into
four disjoint sets, but neither selection nor an unreviewed passing result
automatically satisfies policy. A checkout-local advisory lock serializes
cooperating invocations. Other tools must not write this isolated checkout
during a run; a before/after check cannot detect an external write that is
both applied and reversed between observations. An interrupted mutation leaves
an `inflight.json`; inspect the file and use `mutation_campaign.py --recover
qualification/results/<run>/inflight.json`. Recovery requires the exact pinned
Git source and the recorded original or mutant bytes; unrelated changes fail
closed. A hard termination may leave a compiler child and a temporary PostgreSQL
fixture; inspect the owning process and fixture before retrying.

Capture is limited to 8 MiB and the command deadline. A timeout or capture
overflow kills the original process group, records containment as unknown,
restores the source, and stops the campaign; descendants can escape the group
and may remain alive. This is not proof of process containment or cleanup.
Use an isolated environment and inspect it before retrying. Normal completion
also does not prove a descendant did not detach after closing its output.
The runner calls the resolved pinned Node executable directly: Volta translates
Node exit 1 to 126 on this host, making assertion exits ambiguous. Only exit 1
with a completed assertion-failure summary is counted as a test kill.

The current scanner excludes test and `_fixture` source files. It finds list-head
and literal candidates outside strings, regular expressions, reader-discarded,
quoted, and `(comment ...)` forms. Candidates are syntactic proposals, not a
claim of valid, non-equivalent or covered mutations. The expanded inventory
adds unreviewed integer-boundary/type-predicate proposals and intentionally
performs no mutations. Future work must independently review each operator,
establish comprehensive test coverage, run real test-phase evaluations, dedupe
source identities, and independently review the resulting evidence.

The original exploratory run on `ea44a20631c2c46ae9c5488487f24044cb2bc351`
reported two assertion kills, four survivors and two test errors. The two
claimed kills exited through Volta with code 126 and are no longer counted as
verified kills; 126 cannot distinguish an assertion failure from an execution
failure. The four survivors and two test errors remain distinct observations. All four survivors also survived the existing isolated PostgreSQL/OIDC
integration fixture. Separate test-only candidate `9aedef0dc290754ba73bb13a56828373e3b1112e`
adds four boundary assertions; its counterfactual run kills those four mutants,
but its changed test revision cannot be retroactively attributed to the parent
source's test suite. Those retained local reports are not bundled release proof.
