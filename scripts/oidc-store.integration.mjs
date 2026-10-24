import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { Pool } from 'pg';
import { runHttpFixture } from './oidc-http.fixture.mjs';
import { runAuthorityFixture } from './oidc-protocol.fixture.mjs';
import { initialize, adapterFactory, createProvider, bindSession, sessionGrantActive, installHttp } from '../target/oidc-store/store.js';

// Owned, empty cluster only. No existing service database or credentials used.
const pgBin = process.env.AXXIUM_TEST_PG_BIN || '/usr/lib/postgresql/16/bin';
assert(existsSync(join(pgBin, 'initdb')), 'Set AXXIUM_TEST_PG_BIN to PostgreSQL tools');
const root = mkdtempSync(join(tmpdir(), 'axxium-oidc-test-'));
const data = join(root, 'data');
const socket = createServer();
await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
const port = socket.address().port;
await new Promise(resolve => socket.close(resolve));
let started = false;
let pool;
function run(command, args) {
  const result = spawnSync(join(pgBin, command), args, { encoding: 'utf8' });
  assert.equal(result.status, 0, `${command} failed: ${result.stderr || result.stdout}`);
}
try {
  run('initdb', ['-D', data, '-A', 'trust', '-U', 'oidc_test', '--no-locale']);
  run('pg_ctl', ['-D', data, '-l', join(root, 'postgres.log'), '-o', `-h 127.0.0.1 -p ${port} -k ${root}`, '-w', 'start']);
  started = true;
  pool = new Pool({ host: '127.0.0.1', port, user: 'oidc_test', database: 'postgres', max: 8 });
  await initialize(pool);
  const Factory = adapterFactory(pool);
  const codes = new Factory('AuthorizationCode');
  const sessions = new Factory('Session');
  await codes.upsert('same-id', { accountId: 'synthetic-a', grantId: 'grant-a', userCode: 'fixture-user-code', uid: 'fixture-uid' }, 60);
  await sessions.upsert('same-id', { accountId: 'synthetic-b', grantId: 'grant-b' }, 60);
  assert.equal((await codes.find('same-id')).accountId, 'synthetic-a');
  assert.equal((await sessions.find('same-id')).accountId, 'synthetic-b');
  assert.equal((await codes.findByUserCode('fixture-user-code')).accountId, 'synthetic-a');
  assert.equal((await codes.findByUid('fixture-uid')).accountId, 'synthetic-a');
  assert.equal(await sessions.findByUserCode('fixture-user-code'), null);
  const concurrent = await Promise.allSettled([codes.consume('same-id'), codes.consume('same-id')]);
  assert.equal(concurrent.filter(x => x.status === 'fulfilled').length, 1);
  assert.equal(concurrent.find(x => x.status === 'rejected').reason.error, 'invalid_grant');
  assert.equal(typeof (await codes.find('same-id')).consumed, 'number');
  // Upsert must not erase consumption evidence.
  await codes.upsert('same-id', { accountId: 'synthetic-a', grantId: 'grant-a' }, 60);
  await assert.rejects(codes.consume('same-id'), e => e.error === 'invalid_grant');
  await codes.revokeByGrantId('grant-a');
  assert.equal(await codes.find('same-id'), null);
  assert.equal((await sessions.find('same-id')).accountId, 'synthetic-b');
  // Reconnect to show storage survives adapter/pool replacement.
  await pool.end();
  pool = new Pool({ host: '127.0.0.1', port, user: 'oidc_test', database: 'postgres' });
  const restored = new (adapterFactory(pool))('Session');
  assert.equal((await restored.find('same-id')).accountId, 'synthetic-b');
  await pool.query("UPDATE axxium_oidc_records SET expires_at=clock_timestamp()-interval '1 second'");
  assert.equal(await restored.find('same-id'), null);
  await assert.rejects(restored.consume('same-id'), e => e.error === 'invalid_grant');
  await runAuthorityFixture(pool, createProvider, bindSession, sessionGrantActive);
  await runHttpFixture(pool, installHttp, root);
  console.log('PASS isolated PostgreSQL: model isolation, lookup, concurrent single consumption, durable state, expiry and grant revocation');
} finally {
  if (pool) await pool.end();
  if (started) run('pg_ctl', ['-D', data, '-m', 'immediate', '-w', 'stop']);
  rmSync(root, { recursive: true, force: true });
}
