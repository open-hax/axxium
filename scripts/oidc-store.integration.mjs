import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { randomBytes, createHash, randomUUID, generateKeyPairSync } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import Fastify from 'fastify';
import cookiePlugin from '@fastify/cookie';
import { Pool } from 'pg';
import { runHttpFixture } from './oidc-http.fixture.mjs';
import { runAuthorityFixture } from './oidc-protocol.fixture.mjs';
import { initialize, adapterFactory, createProvider, bindSession, sessionGrantActive, installHttp, bindMailbox, mailboxGrantActive, installOwnerHttp } from '../target/oidc-store/store.js';

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
  const lock = await pool.connect();
  let deadline;
  try {
    await lock.query('BEGIN');
    await lock.query('LOCK TABLE provider_bindings IN ACCESS EXCLUSIVE MODE');
    const guard = new Promise((_, reject) => {
      deadline = setTimeout(() => reject(new Error('Authorization read did not return')), 5000);
    });
    await assert.rejects(Promise.race([active(scoped, actorDid), guard]), /Query read timeout/);
  } finally {
    clearTimeout(deadline);
    await lock.query('ROLLBACK');
    lock.release();
  }
  assert.equal(await active(scoped, actorDid), true, 'The pool recovers after a proof times out');
  assert.equal(await active(scoped, otherActorDid), false, 'A remapped actor requires a new consent grant');
  assert.equal(await active(scoped, null), true, 'Existing event proof remains valid');
  await Grant.upsert(scoped, { ...actorPayload, rejected: { resources: { [resource]: 'mailbox:actors' } } }, 300);
  assert.equal(await active(scoped, actorDid), false, 'Current grant rejection removes actor authority');
  assert.equal(await active(scoped, null), true);
}

async function verifyWorkOwnerConsent(pool, root) {
  const owner = { id: 'owner-consent-human', status: 'active', display_name: 'Existing Axxium Owner' };
  const outsider = { id: 'owner-consent-outsider', status: 'active', display_name: 'Unrelated Human' };
  const work = { id: 'owner-consent-work', status: 'active', display_name: 'Work actor' };
  const rootDid = 'did:web:axxium.example:actors:existing-owner';
  const workDid = `${rootDid}:chatgpt:work`;
  const mailboxDid = 'did:plc:bbbbbbbbbbbbbbbbbbbbbbbb';
  const token = randomBytes(32).toString('base64url');
  const digest = createHash('sha256').update(token).digest('hex');
  const credential = randomUUID();
  const workCredential = randomUUID();
  const workDigest = randomBytes(32).toString('hex');
  await pool.query("INSERT INTO entities VALUES('owner-consent-entity','human'),('owner-consent-outsider-entity','human')");
  await pool.query("INSERT INTO actors VALUES($1,'active','owner-consent-entity'),($2,'active','owner-consent-outsider-entity'),($3,'active',NULL)",
    [owner.id, outsider.id, work.id]);
  await pool.query("INSERT INTO provider_bindings VALUES($1,'atproto',$2)", [work.id, mailboxDid]);
  await pool.query("INSERT INTO sessions VALUES($1,$2,clock_timestamp()+interval '1 hour',$3),($4,$5,clock_timestamp()+interval '1 hour',$6)",
    [owner.id, digest, credential, work.id, workDigest, workCredential]);

  const listener = createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  const origin = `http://127.0.0.1:${port}`;
  const resource = `${origin}/api/mailbox/mcp`;
  const client = { id: 'owner-consent-client', secret: randomBytes(32).toString('hex'),
    'refresh-enabled?': true, 'redirect-uris': [`${origin}/work-callback`] };
  const ordinary = { id: 'ordinary-owner-client', secret: randomBytes(32).toString('hex'),
    'redirect-uris': [`${origin}/ordinary-callback`] };
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const configPath = join(root, 'oidc-owner-consent.json');
  const config = {
    issuer: `${origin}/oidc`, clients: [client, ordinary], 'allow-loopback?': true,
    'cookie-keys': [randomBytes(32).toString('hex')],
    jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), kid: 'work-owner', alg: 'RS256', use: 'sig' }] },
    'mailbox-client-id': client.id, 'mailbox-resource': resource, 'mailbox-actor-messaging?': true,
  };
  const legacyProvider = createProvider(pool, config, id => id === work.id ? work : null);
  async function legacyGrant(scope, actorDid) {
    const account = { accountId: work.id, clientId: client.id };
    const grant = new legacyProvider.Grant({ ...account, expiresIn: 300 });
    grant.addOIDCScope('openid');
    grant.addResourceScope(resource, scope);
    const grantId = await grant.save();
    await bindMailbox(pool, grantId, work.id, workCredential, workDigest, mailboxDid, client.id, resource, actorDid);
    assert.equal(await mailboxGrantActive(pool, grantId, work.id, client.id, resource, actorDid), true);
    const access = new legacyProvider.AccessToken({ ...account, grantId, aud: resource, scope, expiresIn: 300 });
    const refresh = new legacyProvider.RefreshToken({ ...account, grantId, resource, scope: `openid ${scope}`, expiresIn: 300 });
    return { access: await access.save(), refresh: await refresh.save() };
  }
  const legacyEvents = await legacyGrant('mailbox:events', null);
  const legacyActors = await legacyGrant('mailbox:events mailbox:actors', workDid);
  const legacyUnpinnedActors = await legacyGrant('mailbox:events mailbox:actors', null);
  writeFileSync(configPath, JSON.stringify({
    ...config, 'mailbox-owner-consent?': true, 'mailbox-inbox?': true,
  }), { mode: 0o600 });
  const app = Fastify({ logger: false });
  app.register(cookiePlugin);
  let ownerMayManage = true;
  const actors = { [owner.id]: owner, [outsider.id]: outsider, [work.id]: work };
  try {
    const mounted = await installOwnerHttp(app, pool, {
      'config-file': configPath, 'public-base-url': origin, 'session-cookie-name': 'axxium_session',
      'mailbox-actor-did': mailboxDid, 'mailbox-bound-actor-did': workDid, 'mailbox-available?': true,
      'mailbox-owner-authority': { 'owner-actor-id': owner.id, 'root-did': rootDid },
    }, value => value === token ? owner : outsider, id => actors[id],
    value => value === token
      ? { actor: owner, 'actor-id': owner.id, 'cred-id': credential, 'token-hash': digest }
      : { actor: outsider, 'actor-id': outsider.id },
    () => ownerMayManage);
    await app.listen({ port, host: '127.0.0.1' });
    const verifier = randomBytes(32).toString('base64url');
    const request = (url, options) => fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(10000), ...options });
    const ownerCookies = new Map([['axxium_session', token]]);
    async function authorize(selected, cookies, inbox = false) {
      const mailbox = selected === client;
      const scope = mailbox ? `openid mailbox:events mailbox:actors${inbox ? ' mailbox:inbox' : ''}` : 'openid profile';
      const start = new URL(`${origin}/oidc/auth`);
      start.search = new URLSearchParams({ client_id: selected.id, redirect_uri: selected['redirect-uris'][0],
        response_type: 'code', scope,
        ...(mailbox ? { resource } : {}), state: 'owner-consent-state',
        code_challenge_method: 'S256', code_challenge: createHash('sha256').update(verifier).digest('base64url') });
      const cookieHeader = () => [...cookies].map(([key, value]) => `${key}=${value}`).join('; ');
      const remember = response => {
        for (const item of response.headers.getSetCookie()) {
          const [key, ...value] = item.split(';')[0].split('=');
          if (value.join('=')) cookies.set(key, value.join('='));
          else cookies.delete(key);
        }
      };
      let url = start;
      for (let i = 0; i < 12; i++) {
        if (url.href.startsWith(selected['redirect-uris'][0] + '?')) return url.searchParams.get('code');
        let response = await request(url, { headers: { cookie: cookieHeader() } });
        remember(response);
        if (response.status === 403) return null;
        if (response.status === 200 && url.pathname.startsWith('/oidc/interaction/')) {
          const html = await response.text();
          assert.ok(html.includes(owner.display_name));
          assert.equal(html.includes(workDid), mailbox);
          assert.equal(html.includes('read and acknowledge messages'), inbox);
          assert.equal(html.includes('including after you sign out'), mailbox);
          const csrf = html.match(/name=csrf value='([^']+)'/)?.[1];
          assert.ok(csrf);
          response = await request(url, { method: 'POST', headers: { cookie: cookieHeader(), origin,
            'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ csrf, decision: 'approve' }) });
          remember(response);
        }
        const location = response.headers.get('location');
        assert.ok(location, `Consent HTTP ${response.status}: ${await response.text()}`);
        url = new URL(location, origin);
      }
      assert.fail('Consent did not reach its registered callback');
    }
    const form = (path, selected, body) => request(`${origin}/oidc${path}`, { method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: selected.id, client_secret: selected.secret, ...body }) });
    async function exchange(selected, code) {
      assert.ok(code);
      const response = await form('/token', selected, { grant_type: 'authorization_code', code,
        code_verifier: verifier, redirect_uri: selected['redirect-uris'][0], ...(selected === client ? { resource } : {}) });
      const body = await response.json();
      assert.equal(response.status, 200, JSON.stringify(body));
      const claims = JSON.parse(Buffer.from(body.id_token.split('.')[1], 'base64url'));
      assert.equal(claims.sub, owner.id);
      return body;
    }
    async function grantActive(selected, accessToken) {
      const response = await form('/token/introspection', selected, { token: accessToken });
      assert.equal(response.status, 200);
      return (await response.json()).active;
    }
    const ordinaryGrantActive = accessToken => grantActive(ordinary, accessToken);
    assert.equal((await mounted.accessProof(legacyEvents.access))?.authMethod, 'atproto');
    assert.equal(await grantActive(client, legacyEvents.access), true);
    const renewedEvents = await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: legacyEvents.refresh, resource });
    const eventTokens = await renewedEvents.json();
    assert.equal(renewedEvents.status, 200, JSON.stringify(eventTokens));
    assert.equal((await mounted.accessProof(eventTokens.access_token))?.authMethod, 'atproto');
    assert.equal(await mounted.accessProof(legacyActors.access), null, 'Legacy actor access needs fresh owner consent');
    assert.equal(await grantActive(client, legacyActors.access), false);
    for (const previous of [legacyActors, legacyUnpinnedActors]) {
      const deniedRenewal = await form('/token', client, { grant_type: 'refresh_token',
        refresh_token: previous.refresh, resource });
      assert.equal(deniedRenewal.status, 400, 'Legacy actor grants cannot renew under canonical consent');
      assert.equal((await deniedRenewal.json()).error, 'invalid_grant');
    }
    assert.equal(await authorize(client, new Map([['axxium_session', 'unrelated-human']])), null);
    ownerMayManage = false;
    assert.equal(await authorize(client, new Map(ownerCookies)), null, 'An inactive actor cannot receive new consent');
    ownerMayManage = true;
    const original = await exchange(ordinary, await authorize(ordinary, ownerCookies));
    assert.equal(await ordinaryGrantActive(original.access_token), true);
    const issued = await exchange(client, await authorize(client, ownerCookies));
    const reopened = await exchange(ordinary, await authorize(ordinary, ownerCookies));
    assert.equal(await ordinaryGrantActive(original.access_token), true, 'Work consent must retain the preceding ordinary grant');
    assert.equal(await ordinaryGrantActive(reopened.access_token), true);
    assert.equal(typeof issued.refresh_token, 'string');
    const refreshed = await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: issued.refresh_token, resource });
    const rotated = await refreshed.json();
    assert.equal(refreshed.status, 200, JSON.stringify(rotated));
    assert.notEqual(rotated.refresh_token, issued.refresh_token);
    const workToken = rotated.access_token;
    assert.deepEqual(await mounted.accessProof(workToken), {
      subject: owner.id, workAccount: work.id, mailboxDid, principalDid: workDid, authMethod: 'actor-node',
      boundActorDid: workDid,
    });
    const inboxGrantTokens = await exchange(client, await authorize(client, ownerCookies, true));
    const inboxToken = inboxGrantTokens.access_token;
    assert.equal((await mounted.accessProof(inboxToken))?.inbox, true);
    assert.equal(await grantActive(client, inboxToken), true);
    assert.equal((await mounted.accessProof(workToken))?.inbox, undefined, 'Existing outbound grants stay outbound');
    const inboxGrant = await pool.query(
      "UPDATE axxium_oidc_records SET payload=payload || jsonb_build_object('rejected',jsonb_build_object('resources',jsonb_build_object($1::text,'mailbox:inbox')))" +
      " WHERE model='Grant' AND payload->'resources'->>$1::text LIKE '%mailbox:inbox%'", [resource]);
    assert.equal(inboxGrant.rowCount, 1);
    assert.equal(await mounted.accessProof(inboxToken), null, 'A token cannot reuse a rejected inbox scope');
    assert.equal(await grantActive(client, inboxToken), false, 'Introspection respects rejected inbox scope');
    const narrowed = await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: inboxGrantTokens.refresh_token, resource });
    assert.equal(narrowed.status, 200);
    const narrowedToken = (await narrowed.json()).access_token;
    assert.equal((await mounted.accessProof(narrowedToken))?.principalDid, workDid);
    assert.equal((await mounted.accessProof(narrowedToken))?.inbox, undefined, 'Refresh cannot restore a rejected scope');
    assert.equal((await form('/token/revocation', client, { token: narrowedToken, token_type_hint: 'access_token' })).status, 200);
    const remapped = 'did:web:axxium.example:actors:another-owner:work';
    await pool.query('UPDATE axxium_oidc_grant_sessions SET mailbox_actor_did=$1 WHERE mailbox_principal_actor_id=$2',
      [remapped, work.id]);
    assert.equal(await mounted.accessProof(workToken), null, 'A changed actor mapping requires fresh consent');
    await pool.query('UPDATE axxium_oidc_grant_sessions SET mailbox_actor_did=$1 WHERE mailbox_principal_actor_id=$2',
      [workDid, work.id]);
    ownerMayManage = false;
    assert.equal(await mounted.accessProof(workToken), null, 'A suspended actor cannot reuse its grant');
    ownerMayManage = true;
    await pool.query("UPDATE provider_bindings SET actor_id=$1 WHERE subject=$2", [outsider.id, mailboxDid]);
    assert.equal(await mounted.accessProof(workToken), null, 'A reassigned provider subject cannot reuse the grant');
    await pool.query("UPDATE provider_bindings SET actor_id=$1 WHERE subject=$2", [work.id, mailboxDid]);
    const revoked = await form('/token/revocation', client, { token: workToken, token_type_hint: 'access_token' });
    assert.equal(revoked.status, 200);
    assert.equal(await mounted.accessProof(issued.access_token), null, 'Disconnect revokes the original Work grant too');
    assert.equal(await ordinaryGrantActive(original.access_token), true, 'Disconnect must retain the ordinary grant');
    const { rows: [{ t: consentAt }] } = await pool.query('SELECT clock_timestamp() AS t');
    const renewed = await exchange(client, await authorize(client, ownerCookies, true));
    const workGrantEnd = async () => (await pool.query(
      'SELECT expires_at FROM axxium_oidc_grant_sessions WHERE mailbox_principal_actor_id=$1', [work.id])).rows[0].expires_at;
    const consentEnd = await workGrantEnd();
    await pool.query('DELETE FROM sessions WHERE token_hash=$1', [digest]);
    // L5: the owner's session witnessed consent only; Work's grant is its own credential.
    assert.equal((await mounted.accessProof(renewed.access_token))?.principalDid, workDid,
      'Ending the owner browser session does not end the Work node credential');
    const afterLogout = await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: renewed.refresh_token, resource });
    assert.equal(afterLogout.status, 200, 'Work refreshes tokens inside its window after the owner logs out');
    const afterLogoutTokens = await afterLogout.json();
    assert.equal((await mounted.accessProof(afterLogoutTokens.access_token))?.inbox, true,
      'The consented inbox scope renews within the Work grant lifetime after owner logout');
    // Hard cap: the delegated row expires at most one day after consent, and
    // refreshing never moves it.
    const { rows: [cap] } = await pool.query(
      "SELECT expires_at, expires_at <= $2::timestamptz + interval '1 day' AS capped" +
      " FROM axxium_oidc_grant_sessions WHERE mailbox_principal_actor_id=$1", [work.id, consentAt]);
    assert.equal(cap.capped, true, 'The Work grant ends no later than one day after consent');
    assert.equal(cap.expires_at.getTime(), consentEnd.getTime(), 'Refreshing never extends the one-day cap');
    await pool.query("UPDATE axxium_oidc_grant_sessions SET expires_at=clock_timestamp()-interval '1 second'" +
      ' WHERE mailbox_principal_actor_id=$1', [work.id]);
    assert.equal(await mounted.accessProof(afterLogoutTokens.access_token), null,
      'At the one-day cap the Work grant stops working');
    assert.equal((await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: afterLogoutTokens.refresh_token, resource })).status, 400,
      'At the one-day cap refresh is refused; Work needs fresh owner consent');
    await pool.query('UPDATE axxium_oidc_grant_sessions SET expires_at=$2 WHERE mailbox_principal_actor_id=$1',
      [work.id, cap.expires_at]);
    await assert.rejects(authorize(client, new Map(ownerCookies)), /Consent HTTP 4\d\d/,
      'New consent still needs a live owner session');
    ownerMayManage = false;
    assert.equal(await mounted.accessProof(afterLogoutTokens.access_token), null,
      'Loss of the owner manage right still revokes the Work grant');
    assert.equal((await form('/token', client, { grant_type: 'refresh_token',
      refresh_token: afterLogoutTokens.refresh_token, resource })).status, 400, 'and blocks renewal');
    ownerMayManage = true;
  } finally {
    await app.close();
  }
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
  await verifyWorkOwnerConsent(pool, root);
  console.log('PASS mounted Work owner consent: same browser and grants, exact Work mailbox, owner admission, suspension and revocation');
  console.log('PASS isolated PostgreSQL: actor-scoped mailbox grant, remap denial and event compatibility');
  console.log('PASS isolated PostgreSQL: model isolation, lookup, concurrent single consumption, durable state, bounded expired-record/binding reclamation, replay retention and grant revocation');
} finally {
  if (pool) await pool.end();
  if (started) run('pg_ctl', ['-D', data, '-m', 'immediate', '-w', 'stop']);
  rmSync(root, { recursive: true, force: true });
}
