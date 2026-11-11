import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { Pool } from 'pg';
import { runHttpFixture } from './oidc-http.fixture.mjs';
import { runAuthorityFixture } from './oidc-protocol.fixture.mjs';
import { initialize, adapterFactory, createProvider, bindSession, sessionGrantActive, installHttp, bindMailbox, mailboxGrantActive } from '../target/oidc-store/store.js';

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

async function verifyMailboxActorGrant(pool) {
  const account = 'synthetic-mailbox-owner';
  const client = 'synthetic-work';
  const resource = 'https://axxium.example/api/mailbox/mcp';
  const mailboxDid = 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa';
  const actorDid = 'did:web:axxium.example:actors:owner:work';
  const otherActorDid = 'did:web:axxium.example:actors:another:work';
  const credential = '43ae8260-032c-40f3-a2eb-e4edcd8a0985';
  const digest = 'b'.repeat(64);
  await pool.query('ALTER TABLE sessions ADD COLUMN id uuid; CREATE TABLE provider_bindings(actor_id text,provider text,subject text)');
  await pool.query("INSERT INTO actors VALUES($1,'active')", [account]);
  await pool.query("INSERT INTO sessions(actor_id,token_hash,expires_at,id) VALUES($1,$2,clock_timestamp()+interval '1 hour',$3)", [account, digest, credential]);
  await pool.query("INSERT INTO provider_bindings VALUES($1,'atproto',$2)", [account, mailboxDid]);

  const Grant = new (adapterFactory(pool))('Grant');
  const payload = scope => ({ accountId: account, clientId: client, resources: { [resource]: scope } });
  const bind = (id, did) => bindMailbox(pool, id, account, credential, digest, mailboxDid, client, resource, did);
  const active = (id, did) => mailboxGrantActive(pool, id, account, client, resource, did);
  const legacy = 'mailbox-events-only';
  const scoped = 'mailbox-actor-messaging';
  await Grant.upsert(legacy, payload('mailbox:events'), 300);
  await bind(legacy, null);
  assert.equal(await active(legacy, null), true);
  assert.equal(await active(legacy, actorDid), false);

  const actorPayload = payload('mailbox:events mailbox:actors');
  await Grant.upsert(scoped, actorPayload, 300);
  await bind(scoped, actorDid);
  assert.equal(await active(scoped, actorDid), true);
  assert.equal(await active(scoped, otherActorDid), false, 'A remapped actor requires a new consent grant');
  assert.equal(await active(scoped, null), true, 'Existing event proof remains valid');
  await Grant.upsert(scoped, { ...actorPayload, rejected: { resources: { [resource]: 'mailbox:actors' } } }, 300);
  assert.equal(await active(scoped, actorDid), false, 'Current grant rejection removes actor authority');
  assert.equal(await active(scoped, null), true);
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
  // Startup/periodic sweeps are bounded and preserve replay evidence through
  // its lifetime plus a one-day retention window, rather than growing forever.
  await pool.query("INSERT INTO axxium_oidc_records(model,id,payload,expires_at,consumed_at) SELECT 'CleanupFixture','old-'||n,'{}'::jsonb,clock_timestamp()-interval '2 days',1 FROM generate_series(1,1001) n");
  await pool.query("INSERT INTO axxium_oidc_records(model,id,payload,expires_at,consumed_at) VALUES('CleanupFixture','recent','{}',clock_timestamp()-interval '1 hour',1),('CleanupFixture','live','{}',clock_timestamp()+interval '1 hour',1)");
  await pool.query("INSERT INTO axxium_oidc_grant_sessions(grant_id,actor_id,token_hash,expires_at) SELECT 'cleanup-'||n,'synthetic','synthetic',clock_timestamp()-interval '2 days' FROM generate_series(1,1001) n");
  const remaining = async table => Number((await pool.query(`SELECT count(*) AS n FROM ${table} WHERE ${table === 'axxium_oidc_records' ? "model='CleanupFixture' AND id LIKE 'old-%'" : "grant_id LIKE 'cleanup-%'"}`)).rows[0].n);
  await initialize(pool);
  assert.equal(await remaining('axxium_oidc_records'), 501);
  assert.equal(await remaining('axxium_oidc_grant_sessions'), 501);
  await initialize(pool); await initialize(pool);
  assert.equal(await remaining('axxium_oidc_records'), 0);
  assert.equal(await remaining('axxium_oidc_grant_sessions'), 0);
  assert.deepEqual((await pool.query("SELECT id FROM axxium_oidc_records WHERE model='CleanupFixture' ORDER BY id")).rows.map(row => row.id), ['live','recent']);
  await pool.query("DELETE FROM axxium_oidc_records WHERE model='CleanupFixture'");
  await runAuthorityFixture(pool, createProvider, bindSession, sessionGrantActive);
  await runHttpFixture(pool, installHttp, root);
  await verifyMailboxActorGrant(pool);
  console.log('PASS isolated PostgreSQL: actor-scoped mailbox grant, remap denial and event compatibility');
  console.log('PASS isolated PostgreSQL: model isolation, lookup, concurrent single consumption, durable state, bounded expired-record/binding reclamation, replay retention and grant revocation');
} finally {
  if (pool) await pool.end();
  if (started) run('pg_ctl', ['-D', data, '-m', 'immediate', '-w', 'stop']);
  rmSync(root, { recursive: true, force: true });
}
