# Axxium mutation evidence pilot

This producer binds each run to the clean, full application Git HEAD of its own
isolated checkout and records genuine outcomes separately from proposals.
The Services gate requires exact-revision integration, E2E and four disjoint
250-kill batches. Raw reports do not grant production admission or assert those
aggregate receipts; they always retain `qualified_batch: false`. Producer
adoption and the canonical qualification decision are verified separately.
Actual mutation execution requires Linux, a Git checkout with installed locked
npm dependencies, Python 3, Volta and Node 22.23.3. Inventory and the Python
parser/outcome boundary tests require only Python 3 and Git. Native test-driver
laws also require Node; CI provisions the exact 22.23.3 runtime and passes its
resolved executable so an isolated test HOME never invokes a Volta shim.

```sh
python3 -m unittest discover -s qualification -p test_mutation_campaign.py -v
python3 qualification/mutation_campaign.py --inventory
python3 qualification/inspect_expanded_inventory.py
python3 qualification/mutation_campaign.py --path-prefix src/cljs/ --sample-seed exploration-1 --limit 12 --out qualification/results/new-pilot
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

## Fixed application source with a separate accepted test harness

`--external-harness-sha` can select either fixed test-only regression revision
`5b0e9be2170c8b79662077c612efec7bb79f21b8` (eight changed test files) or
`4a7ceeb948c03b9198222c27bb8a8007db2979ea` (the same eight plus the public
boundary test namespace), while the application checkout and
mutation fingerprints remain bound to
`58234df96ca86507e6cdd87f65aaa9ab35621415`. The helper refuses any other
application/harness pair, any non-test Git difference, or a changed immutable
harness. It proves all non-test repository files and modes are identical, then
materializes the harness's test files in a generated project and links each
application source file to the actual fixed checkout. There is one source tree,
so duplicate namespace resolution cannot select the old tracked tests.

The original checkout's tracked tests/configuration remain unchanged. Reports
name the application revision, independent harness revision, immutable generated
file manifest, non-test tree digest, and executed producer Git revision/byte
hashes separately. The producer revision must contain the exact runner/helper
bytes. Matching Git bytes does not prove native review or adoption; qualification
collection must verify that custody separately. Neither a harness proposal nor
an existing regression result is a mutation kill.

These identities name the test-only PR heads. A subsequent main merge containing
qualification tooling cannot replace them: its non-test repository tree differs
from the application. Each harness keeps a separate generated project and the
older project's descriptor remains valid under its original identity.

```sh
python3 qualification/mutation_campaign.py --path-prefix src/cljs/ \
  --external-harness-sha 5b0e9be2170c8b79662077c612efec7bb79f21b8 \
  --producer-revision <accepted-full-producer-commit> \
  --batch-index 0 --sample-seed full-qualification-58234-v1 --limit 1000 \
  --timeout 300 --out qualification/results/batch-0-external-harness
```

Fingerprint partitions and ordering depend on the application source, not the
harness. Replaying the same partition/seed under a stronger harness retains the
same source mutations; preserve both reports and disclose the earlier survivors
or invalid outcomes. Do not add duplicate fingerprints from those reports.
The full selected outcome corpus remains visible even if only completed genuine
test kills can eventually supply the four required 250-kill receipts.

In external-harness mode the source path statically selects relevant test groups
before any mutation runs. Every group includes the CLJS suite; OIDC/config/auth
paths also run the existing isolated PostgreSQL/HTTP protocol fixture, lifecycle
paths run native Node lifecycle/Compose contracts, and rendering/route paths run
the existing browser-script contracts. Each distinct plan has a clean baseline.
Its needed fixture targets are recompiled after every mutation, in the compile
phase. The test driver preserves actual named assertions, Node test events and
group identities; startup/tool/SQL/runtime errors, warnings and timeouts never
supply kills. Native Node fixture readiness and Compose rendering are treated
conservatively as setup rather than positive mutation evidence.

Reports retain each plan, its selection hash, the per-group baseline and result
summaries, and full hashed compiler/test logs. Restored external plans require
the same test/group count and a clean positive-assertion result; exact assertion
totals may differ in lawful protocol race branches and remain recorded. Legacy
CLJS-only runs retain their strict restored-count comparison.

On Linux, send the controller `SIGUSR1` to request a clean stop after its current
mutation. It finishes source restoration and a clean baseline replay before
returning a partial report with `stopped_by_request`. `SIGINT`/`SIGTERM` retain
their stronger interrupted-execution containment semantics below. Run each
worker in its own process/cgroup and checkout; a systemd user service with hard
memory/CPU/task limits and no swap provides an additional native resource bound.

Capture is limited to 8 MiB and the command deadline. A timeout or capture
overflow kills the original process group, records containment as unknown,
restores the source, and stops the campaign; descendants can escape the group
and may remain alive. This is not proof of process containment or cleanup.
Use an isolated environment and inspect it before retrying. Before each compile
or test execution, the runner publishes a durable checkout-wide containment
marker. The import-time Git probe and runtime Volta/Node-version probes are outside
this marker. A SIGINT, SIGTERM, hard interruption, timeout, overflow, or start
failure during a protected execution leaves the marker and blocks new runs and
recovery. After inspecting and dealing with
any remaining processes, an operator can record a specific, non-secret note and
clear the block:

```sh
python3 qualification/mutation_campaign.py --acknowledge-containment --inspection-note 'Describe the actual process inspection and cleanup performed'
```

This archives the attestation; the program cannot verify its truth. Then, if
an `inflight.json` remains, use the existing `--recover` procedure before a new
run. Normal completion also does not prove a descendant did not detach after
closing its output.
The runner calls the resolved pinned Node executable directly: Volta translates
Node exit 1 to 126 on this host, making assertion exits ambiguous. Only exit 1
with a completed assertion-failure summary is counted as a test kill.

The current scanner excludes test and `_fixture` source files. It finds list-head
and literal candidates outside strings, regular expressions, reader-discarded,
quoted, and `(comment ...)` forms. Candidates are syntactic proposals, not a
claim of valid, non-equivalent or covered mutations. Integer boundary and predicate substitutions are exploratory proposals;
the inventory performs no mutations. Seeded selection orders candidate
fingerprints deterministically; it is a bounded exploratory sample and does
not establish representativeness, non-equivalence, or a killed count. Future work must independently review each operator,
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
