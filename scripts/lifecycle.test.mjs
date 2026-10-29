import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const fixture = fileURLToPath(new URL('./lifecycle-child.fixture.mjs', import.meta.url));

function startFixture(overrides = {}) {
  const child = spawn(process.execPath, [fixture], {
    env: { ...process.env, ...overrides }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  let error = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { error += chunk; });
  const readUntil = async (pattern, ms = 5000) => {
    const end = Date.now() + ms;
    while (!pattern.test(output) && Date.now() < end && child.exitCode === null) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.match(output, pattern, `fixture did not reach ${pattern}; stderr: ${error}`);
    return output.match(pattern);
  };
  return { child, readUntil, output: () => output, error: () => error };
}

async function boundedExit(exited, ms = 28000) {
  let guard;
  try {
    return await Promise.race([
      exited,
      new Promise((_, reject) => { guard = setTimeout(() => reject(new Error('child exceeded shutdown bound')), ms); }),
    ]);
  } finally {
    clearTimeout(guard);
  }
}

test('repeated signals during schema initialization close the pool and prevent listening', { timeout: 8000 }, async () => {
  const fixture = startFixture({ FIXTURE_INIT: 'delayed' });
  try {
    await fixture.readUntil(/INIT_STARTED/);
    const exited = once(fixture.child, 'exit');
    fixture.child.kill('SIGTERM');
    fixture.child.kill('SIGINT');
    const [code, signal] = await boundedExit(exited, 6000);
    assert.equal(signal, null);
    assert.equal(code, 0, fixture.error());
    assert.match(fixture.output(), /INIT_CANCELED/);
    assert.doesNotMatch(fixture.output(), /LISTEN/);
    assert.equal((fixture.output().match(/SCHEMA_END/g) ?? []).length, 1);
    assert.equal((fixture.output().match(/POOL_END/g) ?? []).length, 1);
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});

test('signal during asynchronous app setup closes the app and prevents listening', { timeout: 8000 }, async () => {
  const fixture = startFixture({ FIXTURE_SETUP_DELAY: '1' });
  try {
    await fixture.readUntil(/SETUP_STARTED/);
    const exited = once(fixture.child, 'exit');
    fixture.child.kill('SIGTERM');
    const [code, signal] = await boundedExit(exited, 6000);
    assert.equal(signal, null);
    assert.equal(code, 0, fixture.error());
    assert.match(fixture.output(), /SETUP_CANCELED/);
    assert.doesNotMatch(fixture.output(), /LISTEN/);
    assert.equal((fixture.output().match(/SCHEMA_END/g) ?? []).length, 1);
    assert.equal((fixture.output().match(/POOL_END/g) ?? []).length, 1);
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});

test('blocked initialization and pool cleanup are bounded despite repeated signals', { timeout: 31000 }, async () => {
  const fixture = startFixture({ FIXTURE_INIT: 'blocked', FIXTURE_POOL_BLOCKED: '1' });
  try {
    await fixture.readUntil(/INIT_STARTED/);
    const exited = once(fixture.child, 'exit');
    const start = performance.now();
    fixture.child.kill('SIGTERM');
    fixture.child.kill('SIGINT');
    await fixture.readUntil(/SCHEMA_END/);
    const [code, signal] = await boundedExit(exited);
    assert.equal(signal, null);
    assert.equal(code, 1);
    assert.match(fixture.error(), /Axxium shutdown timed out/);
    assert.doesNotMatch(fixture.output(), /LISTEN/);
    assert.equal((fixture.output().match(/SCHEMA_END/g) ?? []).length, 1);
    assert.equal((fixture.output().match(/POOL_END/g) ?? []).length, 0);
    assert.ok(performance.now() - start >= 24000, 'deadline should remain 25 seconds');
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});

test('SIGTERM drains an active HTTP request before closing the database pool', { timeout: 8000 }, async () => {
  const fixture = startFixture();
  try {
    const [, port] = await fixture.readUntil(/LISTEN (\d+)/);
    const response = fetch(`http://127.0.0.1:${port}/hold`);
    await fixture.readUntil(/REQUEST_STARTED/);
    const exited = once(fixture.child, 'exit');
    fixture.child.kill('SIGTERM');
    fixture.child.kill('SIGINT');
    const result = await response;
    assert.equal(result.status, 200);
    assert.deepEqual(await result.json(), { result: 'drained' });
    const [code, signal] = await exited;
    assert.equal(signal, null);
    assert.equal(code, 0, fixture.error());
    const events = fixture.output();
    assert.ok(events.indexOf('REQUEST_STARTED') < events.indexOf('REQUEST_RELEASED'));
    assert.ok(events.indexOf('REQUEST_RELEASED') < events.indexOf('POOL_END'));
    assert.equal((events.match(/POOL_END/g) ?? []).length, 1);
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});

test('pool close failure yields a nonzero exit', { timeout: 8000 }, async () => {
  const fixture = startFixture({ FIXTURE_POOL_FAILURE: '1' });
  try {
    await fixture.readUntil(/LISTEN (\d+)/);
    const exited = once(fixture.child, 'exit');
    fixture.child.kill('SIGTERM');
    const [code, signal] = await exited;
    assert.equal(signal, null);
    assert.equal(code, 1);
    assert.match(fixture.error(), /Axxium shutdown failed/);
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});

test('shutdown deadline survives pool close when another handle keeps Node alive', { timeout: 31000 }, async () => {
  const fixture = startFixture({ FIXTURE_LINGER: '1' });
  try {
    await fixture.readUntil(/LISTEN (\d+)/);
    const exited = once(fixture.child, 'exit');
    const start = performance.now();
    fixture.child.kill('SIGTERM');
    await fixture.readUntil(/POOL_END/);
    const [code, signal] = await boundedExit(exited);
    assert.equal(signal, null);
    assert.equal(code, 1);
    assert.match(fixture.error(), /Axxium shutdown timed out/);
    assert.ok(performance.now() - start >= 24000, 'deadline should remain 25 seconds');
  } finally {
    if (fixture.child.exitCode === null) fixture.child.kill('SIGKILL');
  }
});
