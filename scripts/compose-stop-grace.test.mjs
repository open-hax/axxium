import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { shutdownBound } from '../target/lifecycle/lifecycle.js';

const root = fileURLToPath(new URL('..', import.meta.url));

function durationMs(value) {
  const parts = [...value.matchAll(/(\d+(?:\.\d+)?)(ns|us|µs|ms|s|m|h)/g)];
  assert.equal(parts.map(part => part[0]).join(''), value, 'unrecognized Compose duration');
  const units = { ns: 1e-6, us: 1e-3, 'µs': 1e-3, ms: 1, s: 1000, m: 60000, h: 3600000 };
  return parts.reduce((total, [, quantity, unit]) => total + Number(quantity) * units[unit], 0);
}

function render(schemaTimeout) {
  const env = { PATH: process.env.PATH, HOME: process.env.HOME };
  if (schemaTimeout !== undefined) env.AXXIUM_SCHEMA_STATEMENT_TIMEOUT_MS = String(schemaTimeout);
  const result = spawnSync('docker', [
    'compose', '--project-name', 'axxium-grace-config-test', '--env-file', '/dev/null',
    '-f', 'docker-compose.yml', 'config', '--format', 'json',
  ], { cwd: root, env, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr || String(result.error));
  return JSON.parse(result.stdout).services.axxium;
}

for (const timeout of [undefined, 1000, 20000, 120000, 300000]) {
  test(`Compose grace exceeds startup and ready bounds for schema timeout ${timeout ?? 'default'}`, () => {
    const app = render(timeout);
    const selected = Number(app.environment.AXXIUM_SCHEMA_STATEMENT_TIMEOUT_MS);
    assert.equal(selected, timeout ?? 20000);
    const grace = durationMs(app.stop_grace_period);
    assert.ok(grace > shutdownBound(false, selected), 'startup shutdown deadline must fit within Compose grace');
    assert.ok(grace > shutdownBound(true, selected), 'ready shutdown deadline must fit within Compose grace');
    assert.ok(app.depends_on['axxium-db'], 'database must remain a Compose dependency');
  });
}
