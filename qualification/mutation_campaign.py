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
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
if not re.fullmatch(r"[0-9a-f]{40}", SOURCE_SHA):
    raise ValueError("A full immutable Git revision is required")
SOURCE = ROOT / "src/cljs"
COMPILE = ["volta", "run", "--node", "22.23.3", "node_modules/.bin/shadow-cljs",
           "--config-merge", "{:autorun false}", "compile", "test"]
TEST = ["volta", "run", "--node", "22.23.3", "node", "target/test.cjs"]
REPLACEMENTS = {
    "=": ("not=",), "not=": ("=",), "<": ("<=", ">"), "<=": ("<", ">="),
    ">": (">=", "<"), ">=": (">", "<="), "and": ("or",), "or": ("and",),
    "when": ("when-not",), "when-not": ("when",), "if": ("if-not",), "if-not": ("if",),
    "nil?": ("some?",), "some?": ("nil?",), "pos?": ("neg?", "zero?"),
    "neg?": ("pos?", "zero?"), "zero?": ("pos?",), "true?": ("false?",),
    "false?": ("true?",),
}
ATOM_REPLACEMENTS = {"true": ("false",), "false": ("true",), "0": ("1",), "1": ("0",)}


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
            for replacement in ATOM_REPLACEMENTS.get(token, ()):
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


def counts(output: str):
    summary = re.search(r"Ran (\d+) tests containing (\d+) assertions\.", output)
    outcome = re.search(r"(\d+) failures, (\d+) errors\.", output)
    if not summary or not outcome:
        return None
    return dict(tests=int(summary[1]), assertions=int(summary[2]),
                failures=int(outcome[1]), errors=int(outcome[2]))


def classify_compile(result):
    if result["timed_out"]:
        return "timeout"
    if result["exit_code"] != 0 or not re.search(r"Build completed\..*0 warnings", result["output"]):
        return "compile_failure"
    return "passed"


def classify_test(result, baseline):
    if result["timed_out"]:
        return "timeout"
    summary = counts(result["output"])
    if not summary or summary["tests"] != baseline["tests"] or summary["assertions"] < 1:
        return "invalid_test_output"
    if summary["errors"]:
        return "test_error"
    if summary["failures"] > 0 and result["exit_code"] != 0 and "FAIL in " in result["output"]:
        return "killed"
    if summary["failures"] == 0 and result["exit_code"] == 0:
        return "survived"
    return "invalid_test_exit"


def execute(command, logfile, timeout):
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
        data = {"exit_code": process.returncode, "timed_out": False}
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            output, _ = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            output, _ = process.communicate()
        data = {"exit_code": None, "timed_out": True}
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
        raise
    logfile.write_text(output)
    return {**data, "seconds": round(time.monotonic() - started, 3), "output": output,
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


def run_campaign(candidates, out, limit, timeout, batch_index=None):
    out = owned_output(out)
    if out.exists():
        raise ValueError("Output already exists; preserve earlier evidence")
    out.mkdir(mode=0o700, parents=True)
    node_version = subprocess.check_output(["volta", "run", "--node", "22.23.3", "node", "--version"],
                                           cwd=ROOT, text=True).strip()
    if node_version != "v22.23.3":
        raise ValueError("The test runtime does not match the pinned Node version")
    state = {"source_sha": SOURCE_SHA, "scope": "bounded CLJS node-test pilot", "batch_index": batch_index,
             "producer_sha256": digest(Path(__file__).read_bytes()), "node_version": node_version,
             "package_lock_sha256": digest((ROOT / "package-lock.json").read_bytes()),
             "compile_command": COMPILE, "test_command": TEST,
             "limitations": "Not independently reviewed, not production qualification; no integration or E2E receipt; installed dependency bytes not audited",
             "baseline": None, "candidate_inventory": len(candidates), "results": []}

    def save():
        temporary = out / ".report.json.tmp"
        temporary.write_text(json.dumps(state, indent=2) + "\n")
        temporary.replace(out / "report.json")

    compile_result = execute(COMPILE, out / "baseline.compile.log", timeout)
    if classify_compile(compile_result) != "passed":
        raise RuntimeError("Baseline compiler did not pass with zero warnings")
    test_result = execute(TEST, out / "baseline.test.log", timeout)
    baseline = counts(test_result["output"])
    if (test_result["timed_out"] or test_result["exit_code"] != 0 or not baseline or
            baseline["tests"] <= 0 or baseline["assertions"] <= 0 or baseline["failures"] or baseline["errors"]):
        raise RuntimeError("Baseline test did not complete cleanly")
    state["baseline"] = {**baseline, "compile_log_sha256": compile_result["log_sha256"],
                         "test_log_sha256": test_result["log_sha256"]}
    require_unchanged_tree()
    save()
    for index, candidate in enumerate(candidates[:limit]):
        require_unchanged_tree()
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
        entry = {**candidate, "status": "unverified", "phase": "compile"}
        try:
            path.write_bytes(changed)
            build = execute(COMPILE, out / f"{index:04}.compile.log", timeout)
            require_only_mutant(path, changed)
            entry.update(compile_status=classify_compile(build), compile_seconds=build["seconds"],
                         compile_log_sha256=build["log_sha256"])
            if entry["compile_status"] != "passed":
                entry["status"] = entry["compile_status"]
            else:
                test = execute(TEST, out / f"{index:04}.test.log", timeout)
                require_only_mutant(path, changed)
                entry.update(status=classify_test(test, baseline), phase="test", test_seconds=test["seconds"],
                             test_exit=test["exit_code"], counts=counts(test["output"]), test_log_sha256=test["log_sha256"])
        finally:
            restore(path, original, changed)
            journal.unlink()
        require_unchanged_tree()
        state["results"].append(entry)
        save()
        print(index, candidate["path"], candidate["before"], "=>", candidate["after"], entry["status"], flush=True)
    restored = execute(COMPILE, out / "restored.compile.log", timeout)
    if classify_compile(restored) != "passed":
        raise RuntimeError("Restored source did not compile cleanly")
    final_test = execute(TEST, out / "restored.test.log", timeout)
    if (final_test["timed_out"] or final_test["exit_code"] != 0 or
            counts(final_test["output"]) != baseline):
        raise RuntimeError("Restored source did not reproduce the clean baseline")
    require_unchanged_tree()
    state["restored_baseline"] = "passed"
    state["completed_selection"] = len(state["results"]) == len(candidates)
    state["qualified_batch"] = False  # This pilot is not an admitted policy producer.
    save()
    print("Report:", out / "report.json")


def interrupted(signum, frame):
    raise KeyboardInterrupt(f"Interrupted by signal {signum}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--recover", type=Path, help="Restore only the exact mutation recorded in an owned inflight journal")
    parser.add_argument("--path-prefix", default="src/cljs/axxium/law/oidc.cljc")
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--batch-index", type=int, choices=range(4), default=None,
                        help="Select one disjoint fingerprint partition; no qualification is implied")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--out", type=Path, default=ROOT / "qualification/results/pilot-20261029")
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000 or not 5 <= args.timeout <= 600:
        parser.error("limit must be 1..1000 and timeout 5..600 seconds")
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    with campaign_lock():
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
        if args.inventory:
            print(json.dumps({"source_sha": SOURCE_SHA, "all_candidates": len(candidates),
                              "selected_candidates": len(selected), "paths_with_candidates": len({c["path"] for c in candidates}),
                              "qualification": "unexecuted syntactic candidates, not kills"}, indent=2))
            return
        if not selected:
            raise ValueError("No candidates selected")
        run_campaign(selected, args.out, args.limit, args.timeout, args.batch_index)


if __name__ == "__main__":
    main()
