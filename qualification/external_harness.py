"""Bind accepted regression tests to an unchanged application Git revision.

Only test/config/package copies are immutable here. Live application links may
contain the one mutant admitted by mutation_campaign's own source guards.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess

APPLICATION_SHA = '58234df96ca86507e6cdd87f65aaa9ab35621415'
HARNESS_SHA = '5b0e9be2170c8b79662077c612efec7bb79f21b8'
BOUNDARY_HARNESS_SHA = '4a7ceeb948c03b9198222c27bb8a8007db2979ea'
PROTOCOL_HARNESS_SHA = 'ee15ffff63e4d2c0d0c46645a3444814ba93b596'
JSON_PROTOCOL_HARNESS_SHA = '6e839f6e9b7a8162eaa88dd5795039a196a5dd34'
CHANGED_TEST_PATHS = frozenset({
    'src/cljs/axxium/config_test.cljs',
    'src/cljs/axxium/extern/authorization_boundaries_test.cljs',
    'src/cljs/axxium/extern/codex_findings_test.cljs',
    'src/cljs/axxium/extern/oidc_store_test.cljs',
    'src/cljs/axxium/extern/pg_test.cljs',
    'src/cljs/axxium/law/identity_test.cljs',
    'src/cljs/axxium/law/oidc_test.cljs',
    'src/cljs/axxium/law/relay_test.cljs',
})
DESCRIPTOR = 'external-harness.json'
BUILD_DIRECTORIES = frozenset({'target', '.shadow-cljs', '.cpcache'})
TEST_SUPPORT_PATHS = frozenset({
    'scripts/oidc-protocol.fixture.mjs',
    'scripts/oidc-http.fixture.mjs',
    'scripts/oidc-device.fixture.mjs',
})


def accepted_harnesses():
    """Closed test-only heads; merge commits containing tooling are not harnesses."""
    return {
        HARNESS_SHA: CHANGED_TEST_PATHS,
        BOUNDARY_HARNESS_SHA: CHANGED_TEST_PATHS | {
            'src/cljs/axxium/extern/qualification_boundaries_test.cljs'},
        PROTOCOL_HARNESS_SHA: CHANGED_TEST_PATHS | TEST_SUPPORT_PATHS | {
            'src/cljs/axxium/extern/qualification_boundaries_test.cljs'},
        JSON_PROTOCOL_HARNESS_SHA: CHANGED_TEST_PATHS | TEST_SUPPORT_PATHS | {
            'src/cljs/axxium/extern/qualification_boundaries_test.cljs'},
    }


class HarnessError(ValueError):
    pass


def require(value, message):
    if not value:
        raise HarnessError(message)


def git(root, *args, input=None):
    result = subprocess.run(['git', '-C', str(root), *args], input=input,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    require(result.returncode == 0 and len(result.stdout) <= 32 * 1024 * 1024,
            'External harness Git custody unavailable')
    return result.stdout


def is_test(path):
    value = PurePosixPath(path)
    return value.suffix in {'.cljs', '.cljc'} and value.stem.endswith('_test')


def tree(root, revision):
    result = {}
    for raw in git(root, 'ls-tree', '-rz', '--full-tree', revision).split(b'\0'):
        if not raw:
            continue
        header, path = raw.split(b'\t', 1)
        mode, kind, oid = header.decode('ascii').split(' ')
        path = path.decode('utf-8')
        require(kind == 'blob' and mode in {'100644', '100755', '120000'}
                and re.fullmatch(r'[0-9a-f]{40}', oid), 'Unsupported tracked harness entry')
        relative = PurePosixPath(path)
        require(not relative.is_absolute() and '..' not in relative.parts
                and str(relative) == path and path not in result, 'Unsafe tracked harness path')
        result[path] = {'mode': mode, 'git_oid': oid}
    return result


def blobs(root, identities):
    identities = sorted(set(identities))
    output = io.BytesIO(git(root, 'cat-file', '--batch', input=('\n'.join(identities) + '\n').encode()))
    result = {}
    for oid in identities:
        header = output.readline().decode('ascii').strip().split(' ')
        require(len(header) == 3 and header[:2] == [oid, 'blob'] and header[2].isdecimal(),
                'Git blob custody mismatch')
        size = int(header[2])
        require(size <= 8 * 1024 * 1024, 'Harness file exceeds bounds')
        raw = output.read(size)
        require(len(raw) == size and output.read(1) == b'\n'
                and hashlib.sha1(b'blob ' + str(size).encode() + b'\0' + raw).hexdigest() == oid,
                'Git blob bytes mismatch')
        result[oid] = raw
    require(output.read() == b'', 'Unexpected Git blob response')
    return result


def canonical(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def contract(root, application_sha, harness_sha):
    root = Path(root).resolve(strict=True)
    expected_paths = accepted_harnesses().get(harness_sha)
    require(application_sha == APPLICATION_SHA and expected_paths is not None,
            'Only the fixed application and accepted regression harness are supported')
    require(git(root, 'rev-parse', '--show-toplevel').decode().strip() == str(root)
            and git(root, 'rev-parse', 'HEAD').decode().strip() == APPLICATION_SHA,
            'Worker must retain the exact application Git HEAD')
    application, harness = tree(root, application_sha), tree(root, harness_sha)
    test_support = (TEST_SUPPORT_PATHS if harness_sha in {PROTOCOL_HARNESS_SHA, JSON_PROTOCOL_HARNESS_SHA}
                    else frozenset())
    require(test_support <= application.keys() & harness.keys(),
            'Accepted protocol test-support inputs are missing')
    changed = {path for path in application.keys() | harness.keys()
               if application.get(path) != harness.get(path)}
    require(changed == expected_paths and all(is_test(path) or path in test_support for path in changed),
            'Regression harness differs outside its exact accepted test files')
    original = {path: row for path, row in application.items() if not is_test(path) and path not in test_support}
    require(original == {path: row for path, row in harness.items() if not is_test(path) and path not in test_support},
            'Production or unchanged support repository bytes or modes differ')
    project = root / 'qualification' / 'harness' / harness_sha
    files = {}
    selected = [(path, row, 'live-source') for path, row in application.items()
                if path.startswith('src/cljs/') and PurePosixPath(path).suffix in {'.cljs', '.cljc'}
                and not is_test(path)]
    selected += [(path, row, 'test') for path, row in harness.items()
                 if path.startswith('src/cljs/') and is_test(path)]
    selected += [(path, application[path], 'project')
                 for path in ('shadow-cljs.edn', 'package.json', 'package-lock.json')]
    selected += [(path, harness[path] if path in test_support else row,
                  'test-support' if path in test_support else 'support') for path, row in application.items()
                 if not path.startswith('src/')
                 and path not in {'shadow-cljs.edn', 'package.json', 'package-lock.json'}]
    content = blobs(root, [row['git_oid'] for _, row, _ in selected])
    copies = {}
    for path, row, kind in selected:
        require(row['mode'] in {'100644', '100755'}, 'Generated inputs must be regular Git files')
        destination = 'src/' + path[len('src/cljs/'):] if path.startswith('src/cljs/') else path
        raw = content[row['git_oid']]
        if path == 'shadow-cljs.edn':
            prefix = b':source-paths ["src/cljs"]'
            require(raw.count(prefix) == 1, 'Shadow source-path transformation is ambiguous')
            raw = raw.replace(prefix, b':source-paths ["src"]', 1)
        files[destination] = {**row, 'kind': kind, 'sha256': hashlib.sha256(raw).hexdigest()}
        if kind == 'live-source':
            files[destination]['target'] = str(root / path)
        else:
            copies[destination] = raw
    descriptor = {'schema': 'axxium-external-test-harness/v1', 'application_sha': application_sha,
                  'harness_sha': harness_sha, 'root': str(root), 'project_dir': str(project),
                  'non_test_tree_sha256': hashlib.sha256(canonical(original)).hexdigest(),
                  'changed_test_paths': sorted(changed), 'files': files}
    if test_support:
        # Old manifests remain byte-identical. Only the closed protocol harness
        # substitutes these three test-only modules, excluded from the server
        # build and production Docker copy. All other non-test inputs still match.
        descriptor['test_support_paths'] = sorted(test_support)
    return root, project, descriptor, copies


def require_directory(path):
    require(stat.S_ISDIR(path.lstat().st_mode), 'Harness directory is missing or redirected')


def validate(root: Path, descriptor: dict) -> None:
    require(isinstance(descriptor, dict) and isinstance(descriptor.get('harness_sha'), str),
            'Harness descriptor identity changed')
    root, project, expected, _ = contract(root, APPLICATION_SHA, descriptor['harness_sha'])
    require(isinstance(descriptor, dict) and descriptor == expected, 'Harness descriptor identity changed')
    for directory in (root / 'qualification', root / 'qualification/harness', project, project / 'src'):
        require_directory(directory)
    require((root / 'node_modules').is_dir() and not (root / 'node_modules').is_symlink(),
            'Worker dependencies are unavailable or redirected')
    dependency = project / 'node_modules'
    require(dependency.is_symlink() and os.readlink(dependency) == str(root / 'node_modules'),
            'Harness dependency link changed')
    record = project / DESCRIPTOR
    require(stat.S_ISREG(record.lstat().st_mode) and stat.S_IMODE(record.stat().st_mode) == 0o600
            and record.read_bytes() == canonical(expected), 'Immutable harness descriptor changed')
    observed_files = set()
    for directory, directories, filenames in os.walk(project, followlinks=False):
        for name in list(directories):
            path = Path(directory) / name
            if path.parent == project and name == 'node_modules':
                directories.remove(name)  # Exact dependency link checked above.
            elif path.parent == project and name in BUILD_DIRECTORIES:
                require_directory(path)
                directories.remove(name)  # Compiler outputs are not input copies.
            else:
                require_directory(path)
        observed_files.update(str((Path(directory) / name).relative_to(project)) for name in filenames)
    require(observed_files == set(expected['files']) | {DESCRIPTOR},
            'Generated input membership changed')
    for relative, row in expected['files'].items():
        path = project / relative
        if row['kind'] == 'live-source':
            target = Path(row['target'])
            require(path.is_symlink() and os.readlink(path) == str(target)
                    and stat.S_ISREG(target.lstat().st_mode)
                    and stat.S_IMODE(target.stat().st_mode) & 0o111 == int(row['mode'], 8) & 0o111,
                    'Live application source link changed')
        else:
            require(stat.S_ISREG(path.lstat().st_mode)
                    and stat.S_IMODE(path.stat().st_mode) == int(row['mode'], 8) & 0o777
                    and hashlib.sha256(path.read_bytes()).hexdigest() == row['sha256'],
                    'Immutable generated test or project file changed')


def prepare(root: Path, application_sha: str, harness_sha: str) -> dict:
    root, project, descriptor, copies = contract(root, application_sha, harness_sha)
    for directory in (root / 'qualification', root / 'qualification/harness'):
        if not directory.exists() and not directory.is_symlink():
            directory.mkdir(mode=0o700)
        require_directory(directory)
    if project.exists() or project.is_symlink():
        validate(root, descriptor)
        return descriptor
    project.mkdir(mode=0o700)
    for relative, row in descriptor['files'].items():
        path = project / relative
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if row['kind'] == 'live-source':
            path.symlink_to(row['target'])
        else:
            with path.open('xb') as stream:
                stream.write(copies[relative])
            path.chmod(int(row['mode'], 8) & 0o777)
    (project / 'node_modules').symlink_to(root / 'node_modules', target_is_directory=True)
    record = project / DESCRIPTOR
    with record.open('xb') as stream:
        stream.write(canonical(descriptor))
    record.chmod(0o600)
    validate(root, descriptor)
    return descriptor
