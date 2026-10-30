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
python3 qualification/mutation_campaign.py --operator-id <declared-owner> --path-prefix src/cljs/ --sample-seed exploration-1 --limit 12 --out qualification/results/new-pilot
python3 qualification/mutation_campaign.py --operator-id <declared-owner> --path-prefix src/cljs/ --batch-index 0 --limit 1000 --out qualification/results/batch-0
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

`--external-harness-sha` can select one of the fixed test-only regression revisions
`5b0e9be2170c8b79662077c612efec7bb79f21b8` (eight changed test files) or
`4a7ceeb948c03b9198222c27bb8a8007db2979ea` (the same eight plus the public
boundary test namespace), or the separately pinned protocol harness described
below, while the application checkout and
mutation fingerprints remain bound to
`58234df96ca86507e6cdd87f65aaa9ab35621415`. The helper refuses any other
application/harness pair, any production Git difference, or a changed immutable
harness. It proves production and unchanged support repository files/modes are identical, then
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

These identities name pure test/test-support commits included in native adopted
PR ancestry. A subsequent main merge containing qualification tooling cannot
replace them: its repository tree outside the declared test inputs differs from the application.
Each harness keeps a separate generated project and the older project's
descriptor remains valid under its original identity.

The protocol harness is the pure test commit
`ee15ffff63e4d2c0d0c46645a3444814ba93b596`.
Its separately pinned successor
`6e839f6e9b7a8162eaa88dd5795039a196a5dd34` adds public JSON media-type,
syntax and object-shape assertions before parsing discovery/token/introspection
responses. It preserves the permitted empty 200 successful revocation response.
This makes an observed plaintext 500 fail a measured response assertion rather
than escape as a bare parser error. Older errors remain unchanged observations.
The next pure successor, `9bfa2725a1763380d3bbb55b884bbeed060aa04c`,
preserves an independent error observation for rejected synthetic handlers,
provider error events and rejected queries on the fixture's owned pool. The
pool's original query method is restored in `finally`. The measurement preload
accepts the payload-free observation only from the declared protocol fixture;
an underlying execution error therefore forces `test_error` even when the
HTTP response also fails a JSON assertion. Ordinary response assertions retain
their existing meaning when no underlying error occurred. Older manifests and
raw outcomes are not reinterpreted under this new harness.
It additionally admits exactly `scripts/oidc-protocol.fixture.mjs`,
`scripts/oidc-http.fixture.mjs`, and `scripts/oidc-device.fixture.mjs` as test
support. These modules belong only to the existing isolated integration fixture
chain; the server build and Dockerfile exclude them. The helper copies their
accepted harness bytes, records `test-support` roles and `test_support_paths`,
and verifies every other non-test repository byte/mode against the fixed
application. The application checkout's original scripts remain untouched.
Older harnesses admit no test-support differences and keep their original
descriptor byte shape. Renaming bare `assert(...)` calls to `assert.ok(...)`
preserves their behavior while making actual named assertion execution and
failures visible to the existing measured driver. Infrastructure startup
assertions remain excluded.

The historical `non_test_tree_sha256` digests have explicit scopes. The first
two harnesses bind all 112 files outside the ClojureScript test set. The protocol
harness binds the 109 remaining files after additionally excluding its three
declared test-support modules. Every file in that scope remains identical to
the application revision; each excluded fixture is independently immutable and
hashed in the descriptor. The historical digests are not interchangeable:
compare the declared scope and `test_support_paths` before interpreting a hash.

```sh
python3 qualification/mutation_campaign.py --path-prefix src/cljs/ \
  --operator-id <declared-owner> \
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
python3 qualification/mutation_campaign.py --acknowledge-containment \
  --inspection-author <separate-inspector> \
  --inspection-note 'Describe the actual process inspection and cleanup performed'
```

This archives the attestation; the program cannot verify its truth. Then, if
an `inflight.json` remains, use the existing `--recover` procedure before a new
run. Normal completion also does not prove a descendant did not detach after
closing its output.
The runner calls the resolved pinned Node executable directly: Volta translates
Node exit 1 to 126 on this host, making assertion exits ambiguous. Only exit 1
with a completed assertion-failure summary is counted as a test kill.

The scanner excludes test and `_fixture` source files. The original `legacy-v1`
profile finds list-head and literal candidates outside strings, regular
expressions, reader-discarded, quoted, and `(comment ...)` forms. Candidates are syntactic proposals, not a
claim of valid, non-equivalent or covered mutations. Integer boundary and predicate substitutions are exploratory proposals;
the inventory performs no mutations. Seeded selection orders candidate
fingerprints deterministically; it is a bounded exploratory sample and does
not establish representativeness, non-equivalence, or a killed count. Future work must independently review each operator,
establish comprehensive test coverage, run real test-phase evaluations, dedupe
source identities, and independently review the resulting evidence.

## Stable standard operators and complete selection

The explicit `standard-v2` profile is the CLI default. It retains every
`legacy-v1` fingerprint and adds two standard families: nonempty lexical string
literals become the empty string, and eligible integer literals use the complete
PIT CRCR replacement set `1`, `0`, `-1`, `-c`, `c+1`, `c-1`. Unchanged values and
duplicate replacements are removed. The existing integer eligibility bound is
unchanged; arbitrary extra nearby values are not generated. These families
follow the published [Stryker string mutator](https://stryker-mutator.io/mutators.html/)
and [PIT constant mutator](https://pitest.org/quickstart/mutators/) definitions.

String selection respects complete escaped/multiline lexical spans and excludes
namespace/import/load forms, docstrings, metadata, regular expressions,
characters, tagged literals, quoted/discarded data and comments. Executable SQL
strings remain eligible: an invalid query or other runtime failure remains a
negative outcome, never an assertion kill. This profile does not add relational,
arithmetic, keyword, return-value or statement-deletion families.

At application `58234df96ca86507e6cdd87f65aaa9ab35621415`, the original inventory
contains 1196 proposals. The standard profile contains 2852 distinct proposals:
all 1196 original identities, 994 new constant replacements and 662 string
replacements. The four fingerprint partitions contain 712, 729, 703 and 708
proposals. These are static inventory counts; they predict no killed count.

Before execution, `selection` freezes the application SHA, profile, source path
prefix, partition, ordering seed, complete sorted inventory and exact ordered
partition, with canonical hashes. Reports retain the full candidate/test-plan
map, the exact observed prefix interval, and every unexecuted fingerprint in
order. All raw outcomes remain visible. A smaller limit bounds execution of
that prefix; it does not change or silently trim the frozen selection.

## Canonical restoration and continuation

A timeout or overflow still aborts the invocation and preserves its unknown
containment marker. Inspect the native controller/cgroup, descendants, owned
fixture and source restoration before a separate agent acknowledges it. The
acknowledgement archives the exact aborted log path and marker plus the declared
inspector, note and timestamp. Distinct names are declarations; this program
does not authenticate identity or independently prove the inspection. Retain
the actual separate-agent/native inspection evidence with the campaign.

After acknowledgement, `--restore-from` performs a fresh, unmutated compile and
test of every statically selected plan under the same source, producer, harness
and selection. Its separate restoration receipt and hashed raw logs must pass
before `--continue-from` creates a new output directory. The continuation begins
after the entire observed prefix, including the timeout and every other
negative result. It runs fresh baselines again before further mutations.

```sh
# In the same isolated fixed-source worker, use the same explicit common options
# (profile, path prefix, partition, seed, full harness SHA and producer SHA):
python3 qualification/mutation_campaign.py <common-options> --operator-id <owner> \
  --restore-from qualification/results/aborted/report.json \
  --containment-ack qualification/results/containment-ack-<timestamp>.json \
  --out qualification/results/fresh-restoration
python3 qualification/mutation_campaign.py <common-options> --operator-id <owner> \
  --continue-from qualification/results/aborted/report.json \
  --containment-ack qualification/results/containment-ack-<timestamp>.json \
  --restoration qualification/results/fresh-restoration/restoration.json \
  --limit 1000 --out qualification/results/continued-suffix
```

Continuation recursively verifies all referenced report/restoration bytes,
native classifications and raw-log hashes. It rejects changed source, producer
or harness, altered order/profile/seed, dropped/reordered/duplicated candidates,
missing negatives or tail entries, running histories, false completion and a
failed/stale restoration. The original evidence is never rewritten. All six
executed producer/helper files are bound to their declared Git bytes, including
`campaign_lineage.py`. Git equality is separate from native PR adoption.

The older 1196-proposal campaigns and earlier harnesses lack this full
continuation contract and remain immutable historical observations. They are
not prefixes of a fresh 2852-proposal JSON-harness campaign. No old negative or
timeout is silently relabelled, and no old kill is needed for the new campaign.

## Positive-evidence collection contract

The Services production policy requires four disjoint indexes, each containing
at least 250 distinct actual `phase: test`, `status: killed` outcomes. It does
not require a 100% mutation score over the proposal inventory. The future
collector must verify the adopted producer and pure-harness ancestry, complete
selection and cumulative report lineage, all raw execution hashes/native
classifications, clean baselines/restoration and absence of unresolved
containment. It then deterministically selects the **first 250 eligible actual
test kills in each partition's frozen cumulative order**. There must be no
duplicate fingerprint within a collection or across the four partitions.

Every collection must reference the full raw campaign reports and ancestor
chain, ordered selection manifest, selected fingerprint/outcome/log references,
complete raw counts for every status, and exact unexecuted tail. Survivors,
errors, compile failures, invalid output and timeouts remain disclosed and earn
zero credit. A partition with fewer than 250 verified test kills cannot qualify.
Collection pass describes the selected positive-evidence requirement; it must
never imply every proposal passed, a 100% mutation score, or convert a raw
`qualified_batch: false` report into an admitted receipt. Integration/E2E and
the immutable Services qualification record remain separate admission inputs.
`collect_mutation_evidence.py` implements that collection law separately from
the six immutable running producer files. It grants no deployment admission and
does not publish the resulting evidence or a Services qualification record.

## Read-only positive-evidence collector

The collector is closed to application `58234df96ca86507e6cdd87f65aaa9ab35621415`,
producer `b87a7ddf24d63147f2a8c85a789fb97b55047c9e`, pure harness
`9bfa2725a1763380d3bbb55b884bbeed060aa04c`, profile `standard-v2`, the full
`src/cljs/` scope and original seed `full-qualification-58234-v1`. Native GitHub
reads verify the producer's exact merged PR/main ancestry, the pure-harness
ancestor, and the collector's own exact Git bytes and native merged-main PR.
Neither a copied summary nor the collector's local tests supplies adoption.
Before native adoption or raw-evidence reads, the collector independently checks
its actual six evaluator files against the immutable producer Git blobs and
pins imported evaluator modules to those nonsymlink paths. Worker-side hashes
cannot stand in for the bytes evaluating their evidence.

Use four final reports, one for each partition. They may live in two reused
workers after the two execution waves, but every worker must be restored and
idle. The collector opens the existing canonical campaign lock read-only and
takes a shared nonblocking lease; a running producer's exclusive lease blocks
collection, and a new cooperating producer cannot start while collection reads
that worker. Native unit inactivity/cgroup emptiness, clean source/index,
unchanged six runtime files, immutable harness files, complete ordered selection,
recursive report ancestry and every raw execution hash/classification are
checked. No worker files are written. A terminal aborted report additionally
requires its independently declared containment acknowledgement and canonical
fresh all-plan restoration; a running or unresolved report cannot supply proof.

Positive eligibility requires a unique genuine structured driver summary,
the selected plan/groups and a clean compile/test boundary. Legacy text alone
cannot supply external-harness credit. The owned query-error observer in this
harness covers `runAuthorityFixture`; it is restored before the mounted
HTTP/device fixtures run. Consequently a protocol-group failure after the exact
immutable raw prefix `QUALIFICATION_CHILD[protocol] PASS durable authority:`
earns no credit, including a mixed failure with another group. A genuine
protocol failure before that marker can qualify if its structured summary has
no errors, warnings, timeout or unknown containment. Another group's failure
can qualify when protocol itself passed without failures. The guard records the
pure fixture hash, producer/driver hash, exact marker and explicit exclusion
reasons. It deliberately excludes some potentially valid later assertions; it
does not relabel their raw `killed` observations or claim they are equivalent.
The same guard applies to the 250-kill clean-stop monitor.

Create an input plan with schema `axxium-collection-inputs/v1` and `batches`
containing exactly four objects. Each names `batch_index`, absolute
`worker_root`, absolute `report`, and the corresponding native `unit`. A final
aborted report also names absolute `containment_ack` and `restoration` files.
Units follow `axxium-qual582-standard-v2-v5-worker0.service`, the corresponding
worker1 name, and the `-batch2`/`-batch3` suffixes for the later wave. Canonical
continued invocations append `-continue1` through `-continue999`; zero,
leading-zero and unbounded suffixes refuse. Use a fresh
output under an existing owned private directory outside all workers:

```sh
python3 qualification/collect_mutation_evidence.py \
  --inputs /absolute/collection-inputs.json \
  --collector-revision <accepted-full-collector-head> \
  --out /absolute/collections/new-collection
```

All four partitions must have at least 250 eligible actual kills before any
output is created. `audit.json` preserves every observed row and negative
status, full source/producer/harness/selection manifests, all report/ancestor
and closure references, all exclusions, and every unexecuted fingerprint.
`mutation-batches.json` contains the four compact policy-shaped positive
collections and an audit hash, staying within the Services receipt size budget.
The final `manifest.json` is written last and hashes both files. Output is
private, new-only, and explicitly `production_admitted: false`.

Before immutable Services qualification-record adoption, publish the complete
audit and every referenced raw report/log/acknowledgement/restoration and native
launch/closure receipt with immutable hashes and inspectable custody. A local
path and a collection label alone are not published evidence. The canonical
Services policy still evaluates the exact integration/E2E receipts and four
positive batches; the collector does not reimplement that admission decision.

The original exploratory run on `ea44a20631c2c46ae9c5488487f24044cb2bc351`
reported two assertion kills, four survivors and two test errors. The two
claimed kills exited through Volta with code 126 and are no longer counted as
verified kills; 126 cannot distinguish an assertion failure from an execution
failure. The four survivors and two test errors remain distinct observations. All four survivors also survived the existing isolated PostgreSQL/OIDC
integration fixture. Separate test-only candidate `9aedef0dc290754ba73bb13a56828373e3b1112e`
adds four boundary assertions; its counterfactual run kills those four mutants,
but its changed test revision cannot be retroactively attributed to the parent
source's test suite. Those retained local reports are not bundled release proof.
