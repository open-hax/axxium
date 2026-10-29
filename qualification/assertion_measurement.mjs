// Measurement only. Count named calls in immutable accepted fixture files.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = process.env.AXXIUM_QUAL_SOURCE_ROOT;
const directory = process.env.AXXIUM_QUAL_COUNTER_DIR;
if (!root || !directory) throw new Error('Assertion measurement scope missing');
const allowed = new Set(['oidc-store.integration.mjs', 'oidc-protocol.fixture.mjs',
  'oidc-http.fixture.mjs', 'oidc-device.fixture.mjs', 'lifecycle.test.mjs',
  'compose-stop-grace.test.mjs', 'actor-pages.test.mjs', 'oidc-return.test.mjs']);
const passed = {}, failed = {}, setupFailures = {}, warnings = [];
process.on('axxiumQualificationFixtureError', () => {
  const stack = new Error().stack?.split('\n') ?? [];
  const fixture = root + '/scripts/oidc-protocol.fixture.mjs';
  const observed = stack.some(line => {
    const file = line.match(/(file:\/\/[^\s)]+):\d+:\d+/)?.[1];
    return file && fileURLToPath(file) === fixture;
  });
  if (observed) setupFailures['scripts/oidc-protocol.fixture.mjs'] =
    (setupFailures['scripts/oidc-protocol.fixture.mjs'] ?? 0) + 1;
});
for (const method of ['ok', 'equal', 'notEqual', 'deepEqual', 'notDeepEqual', 'strictEqual',
  'notStrictEqual', 'deepStrictEqual', 'notDeepStrictEqual', 'throws', 'rejects',
  'doesNotThrow', 'doesNotReject', 'ifError', 'match', 'doesNotMatch', 'partialDeepStrictEqual']) {
  const original = assert[method];
  if (typeof original !== 'function') continue;
  assert[method] = function (...args) {
    const stack = new Error().stack?.split('\n') ?? [];
    let path;
    for (const line of stack) {
      const file = line.match(/(file:\/\/[^\s)]+):\d+:\d+/)?.[1];
      if (!file) continue;
      const actual = fileURLToPath(file);
      if (actual.startsWith(root + '/scripts/') && allowed.has(actual.slice((root + '/scripts/').length))) {
        path = actual.slice(root.length + 1); break;
      }
    }
    // PG binary control and Compose rendering are fixture infrastructure.
    // Their assertion failures are errors, never application mutation kills.
    const setup = stack.some(line => /at run \(.*\/scripts\/oidc-store\.integration\.mjs:/.test(line) ||
      /at render \(.*\/scripts\/compose-stop-grace\.test\.mjs:/.test(line) ||
      /at .*readUntil \(.*\/scripts\/lifecycle\.test\.mjs:/.test(line));
    const record = table => { if (path) table[path] = (table[path] ?? 0) + 1; };
    const rejected = error => { record(setup ? setupFailures : failed); throw error; };
    try {
      const value = Reflect.apply(original, this, args);
      if (value && typeof value.then === 'function') return value.then(result => {
        if (!setup) record(passed); return result;
      }, rejected);
      if (!setup) record(passed);
      return value;
    } catch (error) { return rejected(error); }
  };
}
process.on('warning', warning => warnings.push(warning.name || 'RuntimeWarning'));
process.on('exit', () => writeFileSync(join(directory, process.pid + '.json'), JSON.stringify({
  schema: 'axxium-test-assertion-measurement/v1', passed, failed,
  setup_failures: setupFailures, warnings,
}) + '\n', {mode: 0o600}));
