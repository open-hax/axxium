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
import stat
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
LEGACY_PROFILE = "legacy-v1"
STANDARD_PROFILE = "standard-v2"
MUTATION_PROFILES = (LEGACY_PROFILE, STANDARD_PROFILE)


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


def _legacy_sites(source: str):
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


def _string_sites(source: str):
    """Whole runtime string literals only; imports/docs/readers stay opaque.

    This is the standard filled-string-to-empty operation. No string is decoded,
    split, interpolated or multiplied into arbitrary replacement variants.
    Reader nodes retain their exact spans; discarded forms do not occupy a
    semantic child position, including nested reader discards.
    """
    stream = tokens(source)
    close_for = {"(": ")", "[": "]", "{": "}"}

    def value(index, disabled=False):
        children = []
        while True:
            child, index = form(index, disabled)
            children.append(child)
            if child["produces"]:
                return children, index

    def node(kind, token, start, end, disabled, children=(), produces=True):
        return {"kind": kind, "token": token, "start": start, "end": end,
                "disabled": disabled, "children": list(children), "produces": produces}

    def form(index, disabled=False):
        if index >= len(stream):
            raise ValueError("Missing reader form")
        token, start, end = stream[index]
        if token == "#_":
            children, following = value(index + 1, True)
            return node("discard", token, start, children[-1]["end"], True, children, False), following
        if token in {"'", "`", "#'"}:
            children, following = value(index + 1, True)
            return node("reader", token, start, children[-1]["end"], True, children), following
        if token == "^":
            metadata, following = value(index + 1, True)
            actual, following = value(following, disabled)
            return node("metadata", token, start, actual[-1]["end"], disabled,
                        metadata + actual), following
        if token in {"~", "~@", "@", "#?", "#?@", "#", "#js"}:
            children, following = value(index + 1, disabled)
            return node("reader", token, start, children[-1]["end"], disabled, children), following
        if token.startswith("#") and token != "#literal" and not token.startswith("##"):
            # A tagged literal is not necessarily a string value. Its payload
            # is deliberately excluded instead of guessing a custom reader.
            children, following = value(index + 1, True)
            return node("tagged", token, start, children[-1]["end"], True, children), following
        if token in close_for:
            children, following = [], index + 1
            while following < len(stream) and stream[following][0] != close_for[token]:
                if stream[following][0] in close_for.values():
                    raise ValueError("Mismatched reader delimiters")
                child, following = form(following, disabled)
                children.append(child)
            if following >= len(stream):
                raise ValueError("Unclosed reader delimiter")
            return node(token, token, start, stream[following][2], disabled, children), following + 1
        if token in close_for.values():
            raise ValueError("Unexpected reader delimiter")
        return node("atom", token, start, end, disabled), index + 1

    found = []
    definitions = {"def", "defonce", "defn", "defn-", "defmacro", "defmulti"}
    loaders = {"ns", "require", "import", ".require", ".import", "load", "load-file", "load-string"}

    def walk(current, strings_disabled=False):
        if current["disabled"]:
            return
        children = [child for child in current["children"] if child["produces"]]
        head = (children[0]["token"] if current["kind"] == "(" and children
                and children[0]["kind"] == "atom" else None)
        name = head.rsplit("/", 1)[-1] if head else None
        if name == "comment":
            return
        strings_disabled = strings_disabled or name in loaders
        if current["kind"] == "atom":
            raw = source[current["start"]:current["end"]]
            if not strings_disabled and raw.startswith('"') and raw != '""':
                found.append((current["start"], current["end"], raw, '""', "string_literal"))
            return
        for index, child in enumerate(children):
            doc = (name in definitions and index == 2 and len(children) >= 4
                   and source[child["start"]:child["end"]].startswith('"'))
            walk(child, strings_disabled or doc)

    index = 0
    while index < len(stream):
        current, index = form(index)
        walk(current)
    return found


def sites(source: str, profile=LEGACY_PROFILE):
    """Stable legacy sites plus two explicitly declared standard families.

    PIT CRCR uses exactly {1, 0, -1, -c, c+1, c-1}. Existing boundary proposals
    keep their identities; overlapping values and unchanged replacements are
    deduplicated. No family label is added to mutation fingerprint identity.
    """
    if profile not in MUTATION_PROFILES:
        raise ValueError("Unknown mutation profile")
    found = _legacy_sites(source)
    if profile == LEGACY_PROFILE:
        return found
    original_integers = {}
    for start, end, before, _after, kind in found:
        if kind == "literal" and re.fullmatch(r"-?(?:0|[1-9][0-9]{0,8})", before) and before != "-0":
            original_integers[(start, end, before)] = int(before)
    for (start, end, before), number in original_integers.items():
        for replacement in (1, 0, -1, -number, number + 1, number - 1):
            if str(replacement) != before:
                found.append((start, end, before, str(replacement), "literal"))
    found.extend(_string_sites(source))
    return list(dict.fromkeys(found))


def digest(data: bytes):
    return hashlib.sha256(data).hexdigest()


def prepare_evidence_directories():
    """New canonical evidence is owner-only; historical file bytes stay intact."""
    for directory in (ROOT / "qualification", ROOT / "qualification/results"):
        directory.mkdir(mode=0o700, exist_ok=True)
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise ValueError("Evidence directory is redirected or owned by another user")
        directory.chmod(0o700)


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise ValueError("Evidence file is redirected, linked or owned by another user")
        os.fchmod(descriptor, 0o600)
        os.ftruncate(descriptor, 0)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = None
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        if descriptor is not None:
            os.close(descriptor)


def save_json(path, value):
    temporary = path.with_name("." + path.name + ".tmp")
    private_write(temporary, json.dumps(value, indent=2) + "\n")
    temporary.replace(path)
    sync_directory(path.parent)


def static_plan_for_path(path):
    from test_phase import plan_for_path
    return plan_for_path(path)


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
    prepare_evidence_directories()
    directory = ROOT / "qualification/results"
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


def inventory(profile=LEGACY_PROFILE):
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
        for start, end, before, after, kind in sites(text, profile):
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


def acknowledge_containment(note, inspection_author=None):
    """Archive an operator attestation; this does not verify inspection itself."""
    if not note or len(note.strip()) < 15:
        raise ValueError("Provide a specific inspection note of at least 15 characters")
    if inspection_author is not None and (not isinstance(inspection_author, str)
            or not 1 <= len(inspection_author.strip()) <= 128 or "\0" in inspection_author):
        raise ValueError("Provide a bounded declared inspector identity")
    marker = containment_marker()
    if not marker.is_file():
        raise ValueError("No containment marker exists")
    record = {"marker": json.loads(marker.read_text()), "inspection_note": note.strip(),
              "inspection_author": inspection_author, "acknowledger_pid": os.getpid(),
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
    private_write(logfile, output)
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
    files = ["mutation_campaign.py", "campaign_lineage.py"] + (["external_harness.py", "test_phase.py", "assertion_measurement.mjs",
                                         "node_test_reporter.mjs"] if external else [])
    hashes = {}
    for name in files:
        raw = (Path(__file__).parent / name).read_bytes()
        if raw != git("show", revision + ":qualification/" + name):
            raise ValueError("Executed producer differs from its declared Git revision")
        hashes[name] = digest(raw)
    return {"revision": revision, "custody": "executed bytes match Git; native adoption checked separately", "files": hashes}


def checked_execute(command, logfile, timeout, *, harness, execution_root):
    if harness is not None:
        from external_harness import validate
        validate(ROOT, harness)
    result = execute(command, logfile, timeout, cwd=execution_root)
    try:
        summary = driver_summary(result["output"])
    except (ValueError, TypeError):
        summary = None
    if summary and (summary.get("timed_out") or summary.get("containment_unknown")):
        result["timed_out"] = result["timed_out"] or summary.get("timed_out", False)
        result["containment_unknown"] = True
        if not containment_marker().exists():
            mark_execution(logfile)
    if harness is not None:
        validate(ROOT, harness)
    return result


def campaign_plans(candidates, harness, node, compile_command, execution_root, test_command=None):
    plans = {"cljs": {"key": "cljs", "compile_targets": ["test"], "groups": ["cljs"],
                       "compile_command": compile_command,
                       "test_command": test_command or [node, "target/test.cjs"]}}
    mapping = {item["fingerprint"]: "cljs" for item in candidates}
    if harness is not None:
        plans = {}
        for candidate in candidates:
            plan = static_plan_for_path(candidate["path"])
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
            mapping[candidate["fingerprint"]] = key
    return plans, mapping


def run_campaign(candidates, out, limit, timeout, batch_index=None, sample_seed=None,
                 external_harness_sha=None, producer_revision=None, *, selection=None,
                 observed_prefix_start=0, continuation=None, operator_id=None):
    if (type(observed_prefix_start) is not int or not 0 <= observed_prefix_start < len(candidates)):
        raise ValueError("A campaign must execute an unobserved suffix of its complete selection")
    prepare_evidence_directories()
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
        return checked_execute(command, logfile, timeout, harness=harness, execution_root=execution_root)

    node, compile_command, test_command = pinned_commands()
    node_version = subprocess.check_output([node, "--version"],
                                           cwd=ROOT, text=True).strip()
    if node_version != "v22.23.3":
        raise ValueError("The test runtime does not match the pinned Node version")
    plans, candidate_plans = campaign_plans(candidates, harness, node, compile_command, execution_root, test_command)
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
    state.update(selection=selection, operator_id=operator_id or "direct-caller-unattributed",
                 observed_prefix_start=observed_prefix_start,
                 execution_inventory=len(candidates) - observed_prefix_start,
                 completed_selection=False, qualified_batch=False, terminal_state="running")
    if continuation is not None:
        state["continuation"] = continuation

    def save():
        state["observed_prefix_end"] = observed_prefix_start + len(state["results"])
        state["unexecuted_fingerprints"] = [item["fingerprint"] for item in candidates[state["observed_prefix_end"]:]]
        save_json(out / "report.json", state)

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
    for index, candidate in enumerate(candidates[observed_prefix_start:observed_prefix_start + limit]):
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
        private_write(journal, json.dumps({"source_sha": SOURCE_SHA, "path": candidate["path"], "original_sha256": digest(original),
                                          "mutant_sha256": digest(changed), "fingerprint": candidate["fingerprint"]}))
        entry = {**candidate, "test_plan": plan_key, "status": "unverified", "phase": "compile"}
        try:
            path.write_bytes(changed)
            build = run_step(plan["compile_command"], out / f"{index:04}.compile.log")
            require_only_mutant(path, changed)
            entry.update(compile_status=classify_compile(build, len(plan["compile_targets"])), compile_seconds=build["seconds"],
                         compile_log_sha256=build["log_sha256"],
                         compile_containment_unknown=build["containment_unknown"], compile_exit=build["exit_code"],
                         compile_timed_out=build["timed_out"], compile_output_overflow=build["output_overflow"])
            if entry["compile_status"] != "passed":
                entry["status"] = entry["compile_status"]
            else:
                test = run_step(plan["test_command"], out / f"{index:04}.test.log")
                require_only_mutant(path, changed)
                entry.update(status=classify_test(test, baseline), phase="test", test_seconds=test["seconds"],
                             test_exit=test["exit_code"], counts=counts(test["output"]), test_log_sha256=test["log_sha256"],
                             test_containment_unknown=test["containment_unknown"], test_timed_out=test["timed_out"],
                             test_output_overflow=test["output_overflow"])
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
            state["terminal_state"] = "aborted"
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
    state["completed_selection"] = observed_prefix_start + len(state["results"]) == len(candidates)
    state["qualified_batch"] = False  # This pilot is not an admitted policy producer.
    state["terminal_state"] = "complete" if state["completed_selection"] else "clean_partial"
    save()
    print("Report:", out / "report.json")


def restore_campaign(candidates, selection, history, ack, out, timeout, *, producer, harness, operator_id):
    """Fresh unmutated execution only; never rewrite an aborted campaign."""
    from external_harness import validate
    from campaign_lineage import validate_restoration
    require_unchanged_tree()
    require_no_containment_marker()
    validate(ROOT, harness)
    out = owned_output(out)
    if out.exists():
        raise ValueError("Restoration output already exists; retain earlier evidence")
    out.mkdir(mode=0o700, parents=True)
    execution_root = Path(harness["project_dir"])
    node, compile_command, test_command = pinned_commands()
    if subprocess.check_output([node, "--version"], cwd=ROOT, text=True).strip() != "v22.23.3":
        raise ValueError("Restoration requires the pinned Node runtime")
    plans, _ = campaign_plans(candidates, harness, node, compile_command, execution_root, test_command)
    receipt = {"schema": "axxium-campaign-restoration/v1", "source_sha": SOURCE_SHA,
               "selection": selection, "producer": producer, "test_harness": harness,
               "operator_id": operator_id,
               "prior_report": {key: history[key] for key in ("path", "sha256", "next_index")},
               "containment_ack": {key: ack[key] for key in ("path", "sha256")},
               "started_ns": time.time_ns(), "status": "running", "warnings": [],
               "timed_out": False, "baselines": {}, "qualification_credit": False}
    if receipt["started_ns"] <= ack["document"]["acknowledged_ns"]:
        raise ValueError("Restoration must occur after containment acknowledgement")
    target = out / "restoration.json"
    save_json(target, receipt)
    try:
        for key, plan in sorted(plans.items()):
            built = checked_execute(plan["compile_command"], out / f"restore.{key}.compile.log", timeout,
                                    harness=harness, execution_root=execution_root)
            require_unchanged_tree()
            if classify_compile(built, len(plan["compile_targets"])) != "passed":
                receipt["timed_out"] = built["timed_out"]
                raise RuntimeError("Unmutated restoration did not compile cleanly")
            tested = checked_execute(plan["test_command"], out / f"restore.{key}.test.log", timeout,
                                     harness=harness, execution_root=execution_root)
            require_unchanged_tree()
            summary = driver_summary(tested["output"])
            baseline = history["document"]["baselines"][key]
            if (summary is None or classify_test(tested, baseline) != "survived"
                    or tested["containment_unknown"] or summary["failures"] or summary["errors"]
                    or summary["warnings"] or summary["timed_out"]):
                receipt["timed_out"] = tested["timed_out"]
                raise RuntimeError("Unmutated restoration did not reproduce its clean plan")
            receipt["baselines"][key] = {**counts(tested["output"]), "driver_summary": summary,
                                        "compile_log_sha256": built["log_sha256"],
                                        "test_log_sha256": tested["log_sha256"]}
            save_json(target, receipt)
        require_no_containment_marker()
        require_unchanged_tree()
        validate(ROOT, harness)
        receipt.update(status="passed", completed_ns=time.time_ns())
        save_json(target, receipt)
        validate_restoration(sys.modules[__name__], target, history=history, ack=ack, candidates=candidates,
                             selection=selection, producer=producer, harness=harness)
    except BaseException:
        receipt.update(status="failed", completed_ns=time.time_ns())
        save_json(target, receipt)
        raise
    print("Restoration:", target)
    return target


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
    parser.add_argument("--inspection-author", help="Declared independent inspector; requires actual separate-agent evidence")
    parser.add_argument("--recover", type=Path, help="Restore only the exact mutation recorded in an owned inflight journal")
    parser.add_argument("--path-prefix", default="src/cljs/axxium/law/oidc.cljc")
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--batch-index", type=int, choices=range(4), default=None,
                        help="Select one disjoint fingerprint partition; no qualification is implied")
    parser.add_argument("--sample-seed", help="Deterministically order candidate fingerprints for bounded exploration")
    parser.add_argument("--profile", choices=MUTATION_PROFILES, default=STANDARD_PROFILE,
                        help="Explicit stable operator profile; proposal counts never imply kills")
    parser.add_argument("--operator-id", help="Declared campaign owner for containment provenance")
    parser.add_argument("--restore-from", type=Path, help="Run fresh unmutated plans for an acknowledged aborted report")
    parser.add_argument("--continue-from", type=Path, help="Continue the exact prefix of the same canonical selection")
    parser.add_argument("--containment-ack", type=Path, help="Archived independent-inspection attestation bound to the abort")
    parser.add_argument("--restoration", type=Path, help="Fresh canonical all-plan restoration receipt")
    parser.add_argument("--external-harness-sha", help="Separately bound accepted test-only revision; application HEAD stays fixed")
    parser.add_argument("--producer-revision", help="Immutable Git revision whose producer bytes are executing")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--out", type=Path, default=ROOT / "qualification/results/pilot-20261029")
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000 or not 5 <= args.timeout <= 600:
        parser.error("limit must be 1..1000 and timeout 5..600 seconds")
    if sum(bool(value) for value in (args.inventory, args.acknowledge_containment, args.recover,
                                    args.restore_from, args.continue_from)) > 1:
        parser.error("Select one execution, inventory, recovery or acknowledgement mode")
    if args.restoration is not None and args.continue_from is None:
        parser.error("--restoration belongs only to --continue-from")
    if args.containment_ack is not None and not (args.restore_from or args.continue_from):
        parser.error("--containment-ack requires restoration or continuation")
    # Interpret documented relative paths within the worker checkout. Do not
    # resolve or normalize them: the evidence reader must still reject '..'
    # components and inspect every parent for redirected symlinks/ownership.
    for field in ("restore_from", "continue_from", "containment_ack", "restoration"):
        value = getattr(args, field)
        if value is not None and not value.is_absolute():
            setattr(args, field, ROOT / value)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    signal.signal(signal.SIGUSR1, stop_after_current)
    with campaign_lock():
        if args.acknowledge_containment:
            if args.inspection_author is None:
                parser.error("Containment acknowledgement requires --inspection-author")
            print("Recorded operator attestation:", acknowledge_containment(args.inspection_note, args.inspection_author))
            return
        require_no_containment_marker()
        if args.recover:
            if git("rev-parse", "HEAD").decode().strip() != SOURCE_SHA:
                raise ValueError("Recovery requires the pinned Git revision")
            recover(args.recover)
            return
        if list((ROOT / "qualification/results").rglob("inflight.json")):
            raise ValueError("An interrupted mutation must be recovered before another run")
        import campaign_lineage as lineage
        selected, selection = lineage.select_candidates(sys.modules[__name__], profile=args.profile,
            path_prefix=args.path_prefix, batch_index=args.batch_index, sample_seed=args.sample_seed)
        if args.inventory:
            print(json.dumps({"source_sha": SOURCE_SHA, "profile": args.profile,
                              "all_candidates": len(selection["inventory_fingerprints"]),
                              "selected_candidates": len(selected), "selection": selection,
                              "qualification": "unexecuted syntactic candidates, not kills"}, indent=2))
            return
        if (not isinstance(args.operator_id, str) or not 1 <= len(args.operator_id.strip()) <= 128
                or "\0" in args.operator_id):
            parser.error("Execution requires a bounded declared --operator-id")
        start, continuation = 0, None
        if args.restore_from is not None or args.continue_from is not None:
            if not args.external_harness_sha or not args.producer_revision or not args.containment_ack:
                parser.error("Restoration/continuation requires exact harness/producer and --containment-ack")
            from external_harness import prepare, validate
            harness = prepare(ROOT, SOURCE_SHA, args.external_harness_sha)
            validate(ROOT, harness)
            producer = producer_custody(args.producer_revision, external=True)
            history = lineage.load_history(sys.modules[__name__], args.restore_from or args.continue_from,
                candidates=selected, selection=selection, producer=producer, harness=harness)
            ack = lineage.validate_ack(sys.modules[__name__], args.containment_ack, history)
            if args.restore_from is not None:
                restore_campaign(selected, selection, history, ack, args.out, args.timeout,
                    producer=producer, harness=harness, operator_id=args.operator_id.strip())
                return
            if args.restoration is None:
                parser.error("Continuation requires --restoration")
            restored = lineage.validate_restoration(sys.modules[__name__], args.restoration,
                history=history, ack=ack, candidates=selected, selection=selection, producer=producer, harness=harness)
            start = history["next_index"]
            if start >= len(selected):
                raise ValueError("The entire selection is already observed; no suffix may be invented")
            continuation = {"prior_report": {key: history[key] for key in ("path", "sha256", "next_index")},
                            "containment_ack": {key: ack[key] for key in ("path", "sha256")},
                            "restoration": {key: restored[key] for key in ("path", "sha256")}}
        run_campaign(selected, args.out, args.limit, args.timeout, args.batch_index, args.sample_seed,
                     args.external_harness_sha, args.producer_revision, selection=selection,
                     observed_prefix_start=start, continuation=continuation, operator_id=args.operator_id.strip())


if __name__ == "__main__":
    main()
