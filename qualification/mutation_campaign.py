#!/usr/bin/env python3
"""Candidate Axxium mutation pilot. Not an admitted production receipt.

Each invocation binds to an exact Git commit and clean tracked source tree.
This tool only mutates its own isolated checkout. Compile failures, test
errors and timeouts are never counted as kills. Output is evidence for review;
it is not sufficient by itself to satisfy the Services production policy.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
if not re.fullmatch(r"[0-9a-f]{40}", SOURCE_SHA):
    raise ValueError("A full immutable Git revision is required")
SOURCE = ROOT / "src/cljs"
STOP_REQUESTED = False
def pinned_commands():
    """Resolve Node only for execution; parser/law tests need no Node runtime."""
    node = subprocess.check_output(["volta", "run", "--node", "22.23.3", "node", "-p", "process.execPath"],
                                   cwd=ROOT, text=True).strip()
    return (node,
            [node, "node_modules/.bin/shadow-cljs", "--config-merge", "{:autorun false}", "compile", "test"],
            [node, "target/test.cjs"])
REPLACEMENTS = {
    "=": ("not=",), "not=": ("=",), "<": ("<=", ">"), "<=": ("<", ">="),
    ">": (">=", "<"), ">=": (">", "<="), "and": ("or",), "or": ("and",),
    "when": ("when-not",), "when-not": ("when",), "if": ("if-not",), "if-not": ("if",),
    "nil?": ("some?",), "some?": ("nil?",), "pos?": ("neg?", "zero?"),
    "neg?": ("pos?", "zero?"), "zero?": ("pos?",), "true?": ("false?",),
    "false?": ("true?",), "not": ("identity",),
    "string?": ("number?",), "number?": ("string?",),
    "map?": ("vector?",), "vector?": ("map?",), "keyword?": ("string?",),
    "even?": ("odd?",), "odd?": ("even?",), "empty?": ("seq",),
}
ATOM_REPLACEMENTS = {"true": ("false",), "false": ("true",)}


def atom_replacements(token):
    """Syntactic integer boundary proposals; equivalence is not established."""
    if re.fullmatch(r"-?(?:0|[1-9][0-9]{0,8})", token) and token != "-0":
        number = int(token)
        return (str(number - 1), str(number + 1))
    return ATOM_REPLACEMENTS.get(token, ())


def tokens(source: str):
    """Conservative reader tokens with exact character offsets.

    Strings, regex literals, character literals and comments remain opaque.
    The parser below rejects unbalanced forms instead of guessing mutation sites.
    """
    out = []
    i = 0
    while i < len(source):
        char = source[i]
        if char.isspace() or char == ",":
            i += 1
            continue
        if char == ";":
            end = source.find("\n", i)
            i = len(source) if end < 0 else end + 1
            continue
        start = i
        if char == '"' or source.startswith('#"', i):
            i += 2 if char == '#' else 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
            else:
                raise ValueError("Unterminated string or regular expression")
            out.append(("#literal", start, i))
            continue
        if char == "\\":
            i += 1
            if i >= len(source):
                raise ValueError("Truncated character literal")
            if source[i] in "()[]{}\";,`'~@^ ":
                i += 1
            else:
                while i < len(source) and source[i] not in "()[]{}\";,`'~@^ \t\r\n":
                    i += 1
            out.append(("#literal", start, i))
            continue
        dispatch = next((prefix for prefix in ("#?@", "#_", "#?", "#'", "~@")
                         if source.startswith(prefix, i)), None)
        if dispatch:
            i += len(dispatch)
            out.append((dispatch, start, i))
            continue
        if char in "()[]{}'`~@^":
            i += 1
            out.append((char, start, i))
            continue
        # Regex #(), set #{}, var literals, symbolic values and ordinary atoms
        # are decoded only far enough to recognize reader and list boundaries.
        while i < len(source) and not source[i].isspace() and source[i] not in "()[]{}\";,`'~@^":
            i += 1
        if i == start:
            raise ValueError(f"Unrecognized character at {i}")
        out.append((source[start:i], start, i))
    return out


def sites(source: str):
    """Find operator and literal candidates while skipping inert reader forms."""
    stream = tokens(source)
    found = []
    close_for = {"(": ")", "[": "]", "{": "}"}

    def form(index: int, disabled: bool = False):
        if index >= len(stream):
            raise ValueError("Missing reader form")
        token, start, end = stream[index]
        if token in {"'", "`", "#'", "#_"}:
            return form(index + 1, True)
        if token in {"~", "~@", "@", "#?", "#?@"}:
            return form(index + 1, disabled)
        if token == "^":
            return form(form(index + 1, True), disabled)
        if token in close_for:
            closing = close_for[token]
            child = index + 1
            inert = disabled or (token == "(" and child < len(stream) and stream[child][0] == "comment")
            if token == "(" and not inert and child < len(stream):
                head, a, b = stream[child]
                for replacement in REPLACEMENTS.get(head, ()):
                    found.append((a, b, head, replacement, "operator"))
            while child < len(stream) and stream[child][0] != closing:
                if stream[child][0] in close_for.values():
                    raise ValueError("Mismatched reader delimiters")
                child = form(child, inert)
            if child >= len(stream):
                raise ValueError("Unclosed reader delimiter")
            return child + 1
        if token in close_for.values():
            raise ValueError("Unexpected reader delimiter")
        if not disabled:
            for replacement in atom_replacements(token):
                found.append((start, end, token, replacement, "literal"))
        return index + 1

    i = 0
    while i < len(stream):
        i = form(i)
    return found


def digest(data: bytes):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def require_unchanged_tree():
    if git("rev-parse", "HEAD").decode().strip() != SOURCE_SHA:
        raise ValueError("Checkout revision changed during execution")
    if git("diff", "--name-only", "HEAD").strip():
        raise ValueError("Tracked checkout changed during execution")
    if git("ls-files", "--others", "--exclude-standard", "src/cljs").strip():
        raise ValueError("Untracked source can affect the compiled test target")


def owned_output(path):
    results = (ROOT / "qualification/results").resolve()
    if not results.is_relative_to(ROOT.resolve()):
        raise ValueError("Results directory escapes this checkout")
    resolved = (ROOT / path).resolve()
    if resolved == results or not resolved.is_relative_to(results):
        raise ValueError("Output must be a new directory within this checkout's qualification/results")
    return resolved


def batch_for(fingerprint):
    return int(fingerprint, 16) % 4


@contextmanager
def campaign_lock():
    """Serialize cooperating pilot processes for this isolated checkout."""
    directory = ROOT / "qualification/results"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(directory / ".campaign.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError("Another mutation pilot owns this checkout") from exc
        yield
    finally:
        os.close(fd)


def require_only_mutant(path, changed):
    if git("rev-parse", "HEAD").decode().strip() != SOURCE_SHA:
        raise ValueError("Checkout revision changed during mutation")
    changed_paths = git("diff", "--name-only", "HEAD").decode().splitlines()
    if changed_paths != [str(path.relative_to(ROOT))] or path.read_bytes() != changed:
        raise ValueError("Unexpected checkout changes during mutation; no result can be admitted")
    if git("ls-files", "--others", "--exclude-standard", "src/cljs").strip():
        raise ValueError("Untracked source appeared during mutation")


def inventory():
    require_unchanged_tree()
    paths = [p for p in SOURCE.rglob("*") if p.suffix in {".cljs", ".cljc"}
             and not p.stem.endswith("_test") and not p.stem.endswith("_fixture")]
    all_sites = []
    for path in sorted(paths):
        relative = str(path.relative_to(ROOT))
        original = path.read_bytes()
        if original != git("show", "HEAD:" + relative):
            raise ValueError("Source file does not match Git object: " + relative)
        text = original.decode("utf-8")
        for start, end, before, after, kind in sites(text):
            location = len(text[:start].encode("utf-8"))
            identity = json.dumps([SOURCE_SHA, relative, digest(original), location, before, after],
                                  ensure_ascii=False, separators=(",", ":")).encode()
            all_sites.append({"path": relative, "char_start": start, "char_end": end,
                              "byte_offset": location, "source_sha256": digest(original),
                              "before": before, "after": after, "kind": kind,
                              "fingerprint": digest(identity)})
    if len({s["fingerprint"] for s in all_sites}) != len(all_sites):
        raise ValueError("Duplicate candidate fingerprints")
    return all_sites


def driver_summary(output: str):
    prefix = "QUALIFICATION_TEST_SUMMARY="
    lines = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
    if not lines:
        return None
    if len(lines) != 1:
        raise ValueError("Test driver emitted ambiguous summaries")
    data = json.loads(lines[0])
    fields = ("tests", "assertions", "failures", "errors")
    if (not isinstance(data, dict) or data.get("schema") != "axxium-qualification-test-phase/v1"
            or not isinstance(data.get("plan"), str) or not isinstance(data.get("groups"), list)
            or any(type(data.get(key)) is not int or data[key] < 0 for key in fields)
            or not isinstance(data.get("warnings", []), list) or any(not isinstance(w, str) for w in data.get("warnings", []))
            or type(data.get("timed_out", False)) is not bool
            or type(data.get("containment_unknown", False)) is not bool):
        raise ValueError("Test driver summary is malformed")
    names = []
    for group in data["groups"]:
        if (not isinstance(group, dict) or not isinstance(group.get("name"), str)
                or any(type(group.get(key)) is not int or group[key] < 0 for key in fields)
                or not isinstance(group.get("warnings"), list)
                or any(not isinstance(w, str) for w in group["warnings"])
                or type(group.get("timed_out")) is not bool):
            raise ValueError("Test driver group is malformed")
        names.append(group["name"])
    if (len(names) != len(set(names)) or any(data[key] != sum(g[key] for g in data["groups"]) for key in fields)
            or data.get("warnings", []) != [w for g in data["groups"] for w in g["warnings"]]
            or data.get("timed_out", False) != any(g["timed_out"] for g in data["groups"])):
        raise ValueError("Test driver aggregate differs from its measured groups")
    return data


def counts(output: str):
    if "QUALIFICATION_TEST_SUMMARY=" in output:
        try:
            summary = driver_summary(output)
        except (ValueError, TypeError):
            return None
        if summary is None or summary.get("warnings"):
            return None
        return {key: summary[key] for key in ("tests", "assertions", "failures", "errors")}
    summary = re.search(r"Ran (\d+) tests containing (\d+) assertions\.", output)
    outcome = re.search(r"(\d+) failures, (\d+) errors\.", output)
    if not summary or not outcome:
        return None
    return dict(tests=int(summary[1]), assertions=int(summary[2]),
                failures=int(outcome[1]), errors=int(outcome[2]))


def classify_compile(result, expected_builds=1):
    if result["timed_out"]:
        return "timeout"
    if result.get("containment_unknown"):
        return "containment_unknown"
    warnings = re.findall(r"Build completed\.[^\n]*?\b(\d+) warnings\b", result["output"])
    if result["exit_code"] != 0 or len(warnings) != expected_builds or any(int(n) != 0 for n in warnings):
        return "compile_failure"
    return "passed"


def classify_test(result, baseline):
    if result["timed_out"]:
        return "timeout"
    if result.get("containment_unknown"):
        return "containment_unknown"
    try:
        driver = driver_summary(result["output"])
    except (ValueError, TypeError):
        return "invalid_test_output"
    if driver is not None:
        if driver.get("timed_out"):
            return "timeout"
        if driver.get("containment_unknown"):
            return "containment_unknown"
        if (driver["plan"] != baseline.get("driver_plan") or
                [g["name"] for g in driver["groups"]] != baseline.get("driver_groups")):
            return "invalid_test_output"
    summary = counts(result["output"])
    if not summary or summary["tests"] != baseline["tests"] or summary["assertions"] < 1:
        return "invalid_test_output"
    if summary["errors"]:
        return "test_error"
    # Invoke Node directly: Volta maps a child exit of 1 to 126 and cannot
    # distinguish that assertion exit from an execution failure.
    if summary["failures"] > 0 and result["exit_code"] == 1 and (driver is not None or "FAIL in " in result["output"]):
        return "killed"
    if summary["failures"] == 0 and result["exit_code"] == 0:
        return "survived"
    return "invalid_test_exit"


def containment_marker():
    return ROOT / "qualification/results/containment-unknown.json"


def sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def require_no_containment_marker():
    if containment_marker().exists():
        raise RuntimeError("Execution containment unknown; inspect processes and acknowledge the marker before retrying")


def mark_execution(logfile):
    """Fail closed before spawning: an interrupted writer leaves this marker."""
    marker = containment_marker()
    marker.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    payload = {"source_sha": SOURCE_SHA, "state": "execution_active_or_containment_unknown",
               "operator_pid": os.getpid(), "logfile": str(logfile), "started_ns": time.time_ns()}
    fd, temporary = tempfile.mkstemp(prefix=".containment-", dir=marker.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(payload, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, marker)  # Atomic publication, never overwrite an existing block.
        sync_directory(marker.parent)
    finally:
        os.unlink(temporary)


def acknowledge_containment(note):
    """Archive an operator attestation; this does not verify inspection itself."""
    if not note or len(note.strip()) < 15:
        raise ValueError("Provide a specific inspection note of at least 15 characters")
    marker = containment_marker()
    if not marker.is_file():
        raise ValueError("No containment marker exists")
    record = {"marker": json.loads(marker.read_text()), "inspection_note": note.strip(),
              "acknowledged_ns": time.time_ns(), "attestation": "operator assertion, not independently verified"}
    archive = marker.with_name("containment-ack-" + str(record["acknowledged_ns"]) + ".json")
    with archive.open("x") as stream:
        os.chmod(archive, 0o600)
        json.dump(record, stream)
        stream.flush()
        os.fsync(stream.fileno())
    sync_directory(marker.parent)
    marker.unlink()
    sync_directory(marker.parent)
    return archive


def execute(command, logfile, timeout, *, cwd=None):
    """Bounded capture; on timeout or output overflow descendants are unknown."""
    started = time.monotonic()
    deadline = started + timeout
    mark_execution(logfile)
    process = subprocess.Popen(command, cwd=cwd or ROOT, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    os.set_blocking(process.stdout.fileno(), False)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    chunks, size = [], 0
    timed_out = overflow = False
    try:
        while selector.get_map() or process.poll() is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
                break
            for key, _ in selector.select(min(remaining, 0.1)):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                else:
                    size += len(chunk)
                    if size > 8 * 1024 * 1024:
                        overflow = True
                        break
                    chunks.append(chunk)
            if overflow:
                break
    except BaseException:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        raise
    finally:
        if timed_out or overflow:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
        selector.close()
        process.stdout.close()
    output = b"".join(chunks).decode("utf-8", errors="replace")
    logfile.write_text(output)
    if not timed_out and not overflow:
        marker = containment_marker()
        marker.unlink()
        sync_directory(marker.parent)
    return {"exit_code": process.returncode, "timed_out": timed_out,
            "containment_unknown": timed_out or overflow, "output_overflow": overflow,
            "seconds": round(time.monotonic() - started, 3), "output": output,
            "log_sha256": digest(output.encode())}


def restore(path, original, changed):
    observed = path.read_bytes()
    if observed not in (original, changed):
        raise RuntimeError("Source changed concurrently; refusing to overwrite it")
    path.write_bytes(original)
    if path.read_bytes() != original:
        raise RuntimeError("Source restore verification failed")


def recover(journal):
    """Recover only this runner's exact interrupted mutation from pinned Git."""
    results = (ROOT / "qualification/results").resolve()
    journal = journal.resolve(strict=True)
    if not journal.is_relative_to(results) or journal.name != "inflight.json":
        raise ValueError("Recovery requires an owned results inflight journal")
    recovered = journal.with_name("recovered-inflight.json")
    if recovered.exists():
        raise ValueError("Recovery evidence already exists")
    entry = json.loads(journal.read_text())
    if entry.get("source_sha") != SOURCE_SHA:
        raise ValueError("Recovery journal belongs to a different Git revision")
    relative = Path(entry["path"])
    if relative.is_absolute() or ".." in relative.parts or relative.parts[:2] != ("src", "cljs"):
        raise ValueError("Journal source is outside the permitted checkout")
    path = ROOT / relative
    if path.is_symlink() or not path.resolve().is_relative_to((ROOT / "src/cljs").resolve()):
        raise ValueError("Recovery source must be a regular checkout file")
    original = git("show", "HEAD:" + str(relative))
    if digest(original) != entry["original_sha256"]:
        raise ValueError("Journal source does not match the pinned Git object")
    current = path.read_bytes()
    if digest(current) not in {entry["original_sha256"], entry["mutant_sha256"]}:
        raise ValueError("Source changed independently; refusing recovery overwrite")
    if current != original:
        path.write_bytes(original)
    if path.read_bytes() != original:
        raise RuntimeError("Recovery readback failed")
    journal.rename(recovered)
    print("Recovered pinned source:", relative)


def producer_custody(revision, *, external=False):
    """Bind executed producer bytes to Git, without asserting native adoption."""
    if revision is None:
        if external:
            raise ValueError("External harness execution requires an explicit producer revision")
        return {"revision": None, "custody": "unadopted local producer bytes"}
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Producer revision must be a full immutable Git SHA")
    files = ["mutation_campaign.py"] + (["external_harness.py", "test_phase.py", "assertion_measurement.mjs",
                                         "node_test_reporter.mjs"] if external else [])
    hashes = {}
    for name in files:
        raw = (Path(__file__).parent / name).read_bytes()
        if raw != git("show", revision + ":qualification/" + name):
            raise ValueError("Executed producer differs from its declared Git revision")
        hashes[name] = digest(raw)
    return {"revision": revision, "custody": "executed bytes match Git; native adoption checked separately", "files": hashes}


def run_campaign(candidates, out, limit, timeout, batch_index=None, sample_seed=None,
                 external_harness_sha=None, producer_revision=None):
    out = owned_output(out)
    if out.exists():
        raise ValueError("Output already exists; preserve earlier evidence")
    out.mkdir(mode=0o700, parents=True)
    producer = producer_custody(producer_revision, external=external_harness_sha is not None)
    harness = None
    execution_root = ROOT
    if external_harness_sha is not None:
        from external_harness import prepare, validate
        harness = prepare(ROOT, SOURCE_SHA, external_harness_sha)
        execution_root = Path(harness["project_dir"])

    def run_step(command, logfile):
        if harness is not None:
            validate(ROOT, harness)
        result = execute(command, logfile, timeout, cwd=execution_root)
        try:
            summary = driver_summary(result["output"])
        except (ValueError, TypeError):
            summary = None  # The classifier rejects malformed driver output.
        if summary and (summary.get("timed_out") or summary.get("containment_unknown")):
            # A completed outer process cannot prove an aborted child's cleanup.
            result["timed_out"] = result["timed_out"] or summary.get("timed_out", False)
            result["containment_unknown"] = True
            if not containment_marker().exists():
                mark_execution(logfile)
        if harness is not None:
            validate(ROOT, harness)
        return result

    node, compile_command, test_command = pinned_commands()
    node_version = subprocess.check_output([node, "--version"],
                                           cwd=ROOT, text=True).strip()
    if node_version != "v22.23.3":
        raise ValueError("The test runtime does not match the pinned Node version")
    plans = {"cljs": {"key": "cljs", "compile_targets": ["test"],
                       "compile_command": compile_command, "test_command": test_command}}
    candidate_plans = {item["fingerprint"]: "cljs" for item in candidates}
    if harness is not None:
        from test_phase import plan_for_path
        plans = {}
        for candidate in candidates:
            plan = plan_for_path(candidate["path"])
            key, targets = plan.get("key"), plan.get("compile_targets")
            if (not isinstance(key, str) or not re.fullmatch(r"[a-z0-9+-]+", key)
                    or not isinstance(targets, list) or not targets or targets[0] != "test"
                    or len(set(targets)) != len(targets)
                    or not set(targets) <= {"test", "oidc-store-fixture", "lifecycle-fixture"}):
                raise ValueError("The static test-phase plan is malformed")
            plan = {**plan, "compile_command": compile_command[:-1] + targets,
                    "test_command": [sys.executable, str(Path(__file__).with_name("test_phase.py")),
                                     "--plan", key, "--node", node, "--project", str(execution_root)]}
            if key in plans and plans[key] != plan:
                raise ValueError("One test-plan key has inconsistent static commands")
            plans[key] = plan
            candidate_plans[candidate["fingerprint"]] = key
    state = {"source_sha": SOURCE_SHA, "application_under_test": SOURCE_SHA,
             "scope": "exact application source mutations with separately bound test harness", "batch_index": batch_index,
             "sample_seed": sample_seed,
             "producer_sha256": digest(Path(__file__).read_bytes()), "node_version": node_version,
             "package_lock_sha256": digest((ROOT / "package-lock.json").read_bytes()),
             "compile_command": compile_command, "test_command": test_command,
             "test_plans": plans, "candidate_test_plans": candidate_plans,
             "test_plan_selection_sha256": digest(json.dumps(candidate_plans, sort_keys=True).encode()),
             "execution_root": str(execution_root), "producer": producer,
             "test_harness": harness or {"kind": "tracked application tests", "harness_sha": SOURCE_SHA},
             "limitations": "Raw outcomes do not supply production admission; integration/E2E and accepted producer custody are separate; installed dependency bytes not audited",
             "baseline": None, "baselines": {}, "containment": "not_proven",
             "candidate_inventory": len(candidates), "results": []}

    def save():
        temporary = out / ".report.json.tmp"
        temporary.write_text(json.dumps(state, indent=2) + "\n")
        temporary.replace(out / "report.json")

    baselines = {}
    for key, plan in sorted(plans.items()):
        prefix = "baseline" if harness is None else "baseline." + key
        compile_result = run_step(plan["compile_command"], out / (prefix + ".compile.log"))
        if classify_compile(compile_result, len(plan["compile_targets"])) != "passed":
            state["baseline_failure"] = {"plan": key, "phase": "compile", "log_sha256": compile_result["log_sha256"]}
            if compile_result["containment_unknown"]:
                state["containment"] = "unknown_after_aborted_execution"
            save()
            raise RuntimeError("Baseline compiler did not pass cleanly; inspect recorded containment")
        test_result = run_step(plan["test_command"], out / (prefix + ".test.log"))
        baseline = counts(test_result["output"])
        if harness is not None:
            try:
                driver = driver_summary(test_result["output"])
                valid_plan = (driver is not None and driver["plan"] == key
                              and [g["name"] for g in driver["groups"]] == plan["groups"])
            except (ValueError, TypeError):
                valid_plan = False
            if not valid_plan:
                baseline = None
        if (test_result["timed_out"] or test_result["containment_unknown"] or test_result["exit_code"] != 0 or not baseline or
                baseline["tests"] <= 0 or baseline["assertions"] <= 0 or baseline["failures"] or baseline["errors"]):
            state["baseline_failure"] = {"plan": key, "phase": "test", "counts": baseline,
                                         "log_sha256": test_result["log_sha256"]}
            if test_result["containment_unknown"]:
                state["containment"] = "unknown_after_aborted_execution"
            save()
            raise RuntimeError("Baseline test did not complete cleanly; inspect recorded containment")
        if harness is not None:
            baseline = {**baseline, "driver_plan": key, "driver_groups": plan["groups"],
                        "driver_summary": driver}
        baselines[key] = baseline
        state["baselines"][key] = {**baseline, "compile_log_sha256": compile_result["log_sha256"],
                                   "test_log_sha256": test_result["log_sha256"]}
        require_unchanged_tree()
        save()
    state["baseline"] = state["baselines"]["cljs"] if harness is None else {"status": "passed", "plans": sorted(plans)}
    save()
    for index, candidate in enumerate(candidates[:limit]):
        if STOP_REQUESTED:
            state["stopped_by_request"] = True
            break
        require_unchanged_tree()
        plan_key = candidate_plans[candidate["fingerprint"]]
        plan, baseline = plans[plan_key], baselines[plan_key]
        path = ROOT / candidate["path"]
        if path.is_symlink() or not path.resolve().is_relative_to(SOURCE.resolve()):
            raise ValueError("Mutation source must be a regular checkout file")
        original = path.read_bytes()
        if digest(original) != candidate["source_sha256"]:
            raise RuntimeError("Source differs from inventory")
        text = original.decode("utf-8")
        start, end = candidate["char_start"], candidate["char_end"]
        if text[start:end] != candidate["before"]:
            raise RuntimeError("Candidate offset no longer matches")
        changed = (text[:start] + candidate["after"] + text[end:]).encode()
        journal = out / "inflight.json"
        journal.write_text(json.dumps({"source_sha": SOURCE_SHA, "path": candidate["path"], "original_sha256": digest(original),
                                       "mutant_sha256": digest(changed), "fingerprint": candidate["fingerprint"]}))
        entry = {**candidate, "test_plan": plan_key, "status": "unverified", "phase": "compile"}
        try:
            path.write_bytes(changed)
            build = run_step(plan["compile_command"], out / f"{index:04}.compile.log")
            require_only_mutant(path, changed)
            entry.update(compile_status=classify_compile(build, len(plan["compile_targets"])), compile_seconds=build["seconds"],
                         compile_log_sha256=build["log_sha256"],
                         compile_containment_unknown=build["containment_unknown"])
            if entry["compile_status"] != "passed":
                entry["status"] = entry["compile_status"]
            else:
                test = run_step(plan["test_command"], out / f"{index:04}.test.log")
                require_only_mutant(path, changed)
                entry.update(status=classify_test(test, baseline), phase="test", test_seconds=test["seconds"],
                             test_exit=test["exit_code"], counts=counts(test["output"]), test_log_sha256=test["log_sha256"],
                             test_containment_unknown=test["containment_unknown"])
                if harness is not None:
                    try:
                        entry["driver_summary"] = driver_summary(test["output"])
                    except (ValueError, TypeError):
                        entry["driver_summary"] = None  # Full malformed output remains in the hashed log.
        finally:
            restore(path, original, changed)
            journal.unlink()
        require_unchanged_tree()
        state["results"].append(entry)
        unknown = build["containment_unknown"] or (entry["phase"] == "test" and test["containment_unknown"])
        if unknown:
            state["containment"] = "unknown_after_aborted_execution"
        save()
        if unknown:
            raise RuntimeError("Execution containment unknown; source restored; inspect processes before retrying")
        print(index, candidate["path"], candidate["before"], "=>", candidate["after"], entry["status"], flush=True)
    state["restored_baselines"] = {}
    for key, plan in sorted(plans.items()):
        prefix = "restored" if harness is None else "restored." + key
        restored = run_step(plan["compile_command"], out / (prefix + ".compile.log"))
        if classify_compile(restored, len(plan["compile_targets"])) != "passed":
            if restored["containment_unknown"]:
                state["containment"] = "unknown_after_aborted_execution"
            save()
            raise RuntimeError("Restored source did not compile cleanly; inspect recorded containment")
        final_test = run_step(plan["test_command"], out / (prefix + ".test.log"))
        restored_counts = counts(final_test["output"])
        clean = (restored_counts is not None and restored_counts["tests"] == baselines[key]["tests"]
                 and restored_counts["assertions"] > 0 and not restored_counts["failures"] and not restored_counts["errors"]
                 and classify_test(final_test, baselines[key]) == "survived")
        if harness is None:
            clean = clean and restored_counts == baselines[key]
        if (final_test["timed_out"] or final_test["containment_unknown"] or final_test["exit_code"] != 0 or not clean):
            if final_test["containment_unknown"]:
                state["containment"] = "unknown_after_aborted_execution"
            save()
            raise RuntimeError("Restored source did not reproduce the clean test plan")
        state["restored_baselines"][key] = {**restored_counts, "compile_log_sha256": restored["log_sha256"],
                                           "test_log_sha256": final_test["log_sha256"]}
        if harness is not None:
            state["restored_baselines"][key]["driver_summary"] = driver_summary(final_test["output"])
    require_unchanged_tree()
    state["restored_baseline"] = "passed"
    state["completed_selection"] = len(state["results"]) == len(candidates)
    state["qualified_batch"] = False  # This pilot is not an admitted policy producer.
    save()
    print("Report:", out / "report.json")


def interrupted(signum, frame):
    raise KeyboardInterrupt(f"Interrupted by signal {signum}")


def stop_after_current(signum, frame):
    """SIGUSR1 requests a clean baseline-restored stop between mutations."""
    global STOP_REQUESTED
    STOP_REQUESTED = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--acknowledge-containment", action="store_true", help="Record operator inspection and clear the containment block")
    parser.add_argument("--inspection-note", help="Specific operator inspection performed before acknowledgement")
    parser.add_argument("--recover", type=Path, help="Restore only the exact mutation recorded in an owned inflight journal")
    parser.add_argument("--path-prefix", default="src/cljs/axxium/law/oidc.cljc")
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--batch-index", type=int, choices=range(4), default=None,
                        help="Select one disjoint fingerprint partition; no qualification is implied")
    parser.add_argument("--sample-seed", help="Deterministically order candidate fingerprints for bounded exploration")
    parser.add_argument("--external-harness-sha", help="Separately bound accepted test-only revision; application HEAD stays fixed")
    parser.add_argument("--producer-revision", help="Immutable Git revision whose producer bytes are executing")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--out", type=Path, default=ROOT / "qualification/results/pilot-20261029")
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000 or not 5 <= args.timeout <= 600:
        parser.error("limit must be 1..1000 and timeout 5..600 seconds")
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    signal.signal(signal.SIGUSR1, stop_after_current)
    with campaign_lock():
        if args.acknowledge_containment:
            print("Recorded operator attestation:", acknowledge_containment(args.inspection_note))
            return
        require_no_containment_marker()
        if args.recover:
            if git("rev-parse", "HEAD").decode().strip() != SOURCE_SHA:
                raise ValueError("Recovery requires the pinned Git revision")
            recover(args.recover)
            return
        if list((ROOT / "qualification/results").rglob("inflight.json")):
            raise ValueError("An interrupted mutation must be recovered before another run")
        candidates = inventory()
        selected = [c for c in candidates if c["path"].startswith(args.path_prefix)]
        if args.batch_index is not None:
            selected = [c for c in selected if batch_for(c["fingerprint"]) == args.batch_index]
        if args.sample_seed is not None:
            selected.sort(key=lambda c: digest((args.sample_seed + ":" + c["fingerprint"]).encode()))
        if args.inventory:
            print(json.dumps({"source_sha": SOURCE_SHA, "all_candidates": len(candidates),
                              "selected_candidates": len(selected), "paths_with_candidates": len({c["path"] for c in candidates}),
                              "qualification": "unexecuted syntactic candidates, not kills"}, indent=2))
            return
        if not selected:
            raise ValueError("No candidates selected")
        run_campaign(selected, args.out, args.limit, args.timeout, args.batch_index, args.sample_seed,
                     args.external_harness_sha, args.producer_revision)


if __name__ == "__main__":
    main()
