// SPDX-License-Identifier: GPL-3.0-or-later
// Production authority and broker adapters; synthetic identities/keys only.
// Default mode is socket-free. --postgres runs the SAME cases with an owned,
// empty PostgreSQL cluster and the real production replay-store SQL adapter.
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, generateKeyPairSync } from 'node:crypto';
import { writeFileSync, mkdtempSync, mkdirSync, chmodSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { Duplex } from 'node:stream';
import Fastify from 'fastify';
import { Pool } from 'pg';
import { SignJWT } from 'jose';
import { installHttp } from '../target/oidc-store/store.js';

const proxx = resolve(process.env.PROXX_RELAY_WORKTREE || '../relay-proxx');
const load = path => import(pathToFileURL(join(proxx, path)).href);
const runtime = await load('dist/cljs/proxx-runtime.js');
const { setActiveCljsRuntime } = await load('dist/lib/cljs-runtime.js');
const { registerWebSocketRoutes } = await load('dist/routes/api/ui/ws.js');
const { SqlFederationStore } = await load('dist/lib/db/sql-federation-store.js');
const { CREATE_RELAY_LEASE_USES_TABLE } = await load('dist/lib/db/schema.js');
const { resolveRequestAuth } = await load('dist/lib/request-auth.js');
const { resolveFederationOwnerSubject } = await load('dist/lib/federation/federation-helpers.js');
const { createFederationBridgeAgent } = await load('dist/lib/federation/bridge-agent.js');
const { createEnvFederationBridgeAgent } = await load('dist/lib/federation/bridge-agent-autostart.js');

mkdirSync('target/relay-tmp', { recursive: true });
const root = mkdtempSync(resolve('target/relay-'));
const pgBin = process.env.AXXIUM_TEST_PG_BIN || '/usr/lib/postgresql/16/bin';
const postgres = process.argv.includes('--postgres');
let started = false, pool, broker, authority;
const originalFetch = globalThis.fetch;
const oldEnv = { ...process.env };
const issuer = 'https://synthetic-authority.example/oidc';
const audience = 'https://synthetic-broker.example';
const actor = { id: 'synthetic-relay-actor', status: 'active' };
const nodeSecret = randomBytes(32).toString('hex');
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const config = { issuer, clients: [{ id: 'synthetic-browser', secret: randomBytes(32).toString('hex'), 'redirect-uris': ['https://browser.example/callback'] }],
  'cookie-keys': [randomBytes(32).toString('hex')],
  jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), kid: 'synthetic-relay-key', alg: 'RS256', use: 'sig' }] },
  'node-grants': [{ 'node-id': 'synthetic-node', secret: nodeSecret, 'owner-subject': actor.id,
    'broker-audience': audience, 'expires-at': Math.floor(Date.now() / 1000) + 86400,
    enabled: true, 'allowed-models': ['test-model'] }] };
const configPath = join(root, 'private.json');
const writeConfig = () => writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
const pgRun = (cmd, args) => {
  const r = spawnSync(join(pgBin, cmd), args, { encoding: 'utf8' });
  assert.equal(r.status, 0, `${cmd} failed: ${r.stderr || r.stdout}${existsSync(join(root, "postgres.log")) ? readFileSync(join(root, "postgres.log"), "utf8") : ""}`);
};
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

// No TCP listener: the actual ws upgrade/decoder/close logic runs on a Duplex.
class WireSocket extends Duplex {
  chunks = [];
  _read() {}
  _write(chunk, _encoding, done) { this.chunks.push(Buffer.from(chunk)); done(); }
  setTimeout() { return this; }
  setNoDelay() { return this; }
  frame(message) {
    const data = Buffer.from(JSON.stringify(message));
    const head = data.length < 126 ? Buffer.from([0x81, 0x80 | data.length])
      : Buffer.from([0x81, 0xfe, data.length >> 8, data.length & 255]);
    this.push(Buffer.concat([head, Buffer.alloc(4), data]));
  }
}
const hello = ownerSubject => ({ type: 'hello', protocolVersion: 'bridge-ws-v0', sentAt: new Date().toISOString(),
  traceId: randomUUID(), ownerSubject, clusterId: 'synthetic-cluster', agentId: 'synthetic-node',
  peerDid: 'operator-node:synthetic-node', environment: 'test', bridgeAgentVersion: 'test', authMode: 'axxium_node', labels: [] });
let tests = 0;
async function check(name, fn) { await fn(); tests++; console.log(`PASS ${name}`); }
try {
  if (postgres) {
    assert(existsSync(join(pgBin, 'initdb')), 'Set AXXIUM_TEST_PG_BIN');
    pgRun('initdb', ['-D', join(root, 'pg'), '-A', 'trust', '-U', 'relay_test', '--no-locale']);
    // Unix socket only, owned path, no existing cluster/database or credentials.
    pgRun('pg_ctl', ['-D', join(root, 'pg'), '-l', join(root, 'postgres.log'), '-o', `-h '' -k ${root}`, '-w', 'start']);
    started = true;
    pool = new Pool({ host: root, user: 'relay_test', database: 'postgres', max: 8 });
    await pool.query('CREATE TABLE actors(id text PRIMARY KEY, status text NOT NULL)');
    await pool.query('INSERT INTO actors VALUES ($1,$2)', [actor.id, actor.status]);
  } else {
    // Explicitly a SQL transport double; this mode makes NO PostgreSQL claim.
    pool = { query: async () => ({ rows: [] }) };
  }
  writeConfig();
  authority = Fastify({ logger: false, trustProxy: true });
  await installHttp(authority, pool, { 'config-file': configPath, 'public-base-url': 'https://synthetic-authority.example', 'session-cookie-name': 'synthetic-root' },
    async () => null, async id => postgres ? (await pool.query('SELECT * FROM actors WHERE id=$1', [id])).rows[0] : id === actor.id ? { ...actor } : null);
  const issue = async (body = { 'node-id': 'synthetic-node', secret: nodeSecret }, secure = true) => authority.inject({ method: 'POST', url: '/api/federation/relay-lease',
    headers: secure ? { 'x-forwarded-proto': 'https' } : {}, payload: body });
  const lease = async () => { const r = await issue(); assert.equal(r.statusCode, 200, r.body); return r.json().lease; };
  let outage = false;
  globalThis.fetch = async (url, options = {}) => {
    const path = String(url);
    if (path.startsWith('https://synthetic-authority.example/')) {
      if (outage) throw new Error('synthetic outage');
      const response = await authority.inject({ method: options.method || 'GET', url: new URL(path).pathname,
        headers: { ...(options.headers || {}), host: 'synthetic-authority.example', 'x-forwarded-proto': 'https' }, payload: options.body });
      return new Response(response.body, { status: response.statusCode, headers: response.headers });
    }
    throw new Error('Unexpected external request in synthetic fixture');
  };
  process.env.AXXIUM_RELAY_ISSUER = issuer;
  process.env.AXXIUM_RELAY_AUDIENCE = audience;
  process.env.AXXIUM_RELAY_NODES = JSON.stringify([{ nodeId: 'synthetic-node', subject: actor.id }]);
  let sql;
  const seen = new Set();
  const queries = [];
  if (postgres) {
    await pool.query(CREATE_RELAY_LEASE_USES_TABLE);
    sql = { unsafe: async (text, values) => (await pool.query(text, values)).rows };
  } else {
    sql = { unsafe: async (text, values) => {
      queries.push(text);
      if (text.startsWith('DELETE')) return [];
      const key = JSON.stringify(values.slice(0, 2));
      if (seen.has(key)) return [];
      seen.add(key); return [{ jti: values[1] }];
    } };
  }
  let store = new SqlFederationStore(sql);
  const createBroker = () => runtime.createRelayBroker((iss, jti, exp) => store.consumeRelayLease(iss, jti, exp));
  const auth = createBroker();
  await check('production signing, existing public JWKS and strict verification', async () => {
    const jwt = await lease();
    const identity = await auth.admit(`Bearer ${jwt}`);
    assert.equal(identity.authKind, 'axxium_node');
    assert.equal(identity.relay.nodeId, 'synthetic-node');
    assert(identity.relay.expiresAt <= Date.now() + 25000);
    assert.equal(identity.subject, actor.id);
    const jwks = (await authority.inject({ url: '/oidc/jwks', headers: { 'x-forwarded-proto': 'https' } })).json();
    assert.equal(jwks.keys[0].kid, config.jwks.keys[0].kid); assert(!('d' in jwks.keys[0]));
  });
  await check('closed node credentials, TLS and browser refusal', async () => {
    for (const body of [{ 'node-id': 'unknown', secret: nodeSecret }, { 'node-id': 'synthetic-node', secret: 'x'.repeat(32) },
      { 'node-id': 'synthetic-node', secret: nodeSecret, owner: actor.id }, { client_id: config.clients[0].id, client_secret: config.clients[0].secret }, {}])
      assert.equal((await issue(body)).statusCode, 403);
    assert.equal((await issue(undefined, false)).statusCode, 403);
  });
  await check('current file removal/disable/expiry/model/schema/key/mode refusals', async () => {
    const grant = config['node-grants'][0];
    for (const changed of [[], [{ ...grant, enabled: false }], [{ ...grant, 'expires-at': Math.floor(Date.now()/1000)-1 }],
      [{ ...grant, 'expires-at': Math.floor(Date.now()/1000)+31*86400 }], [{ ...grant, 'allowed-models': ['*'] }],
      [{ ...grant, secret: config.clients[0].secret }], [{ ...grant, 'owner-subject': 'not-an-actor' }], [{ ...grant, did: 'did:web:host' }]]) {
      config['node-grants'] = changed; writeConfig(); assert.equal((await issue()).statusCode, 403);
    }
    config['node-grants'] = [grant]; writeConfig();
    chmodSync(configPath, 0o644); assert.equal((await issue()).statusCode, 403); chmodSync(configPath, 0o600);
    const kid = config.jwks.keys[0].kid; config.jwks.keys[0].kid = 'changed'; writeConfig(); assert.equal((await issue()).statusCode, 403);
    config.jwks.keys[0].kid = kid; writeConfig();
    writeFileSync(configPath, '{'); assert.equal((await issue()).statusCode, 403); writeConfig();
    const secret = grant.secret; grant.secret = randomBytes(32).toString('hex'); writeConfig(); assert.equal((await issue()).statusCode, 403);
    grant.secret = secret; writeConfig(); assert.equal((await issue()).statusCode, 200);
  });
  await check('actor disable immediately refuses issuance', async () => {
    actor.status = 'disabled';
    if (postgres) await pool.query("UPDATE actors SET status='disabled'");
    assert.equal((await issue()).statusCode, 403);
    actor.status = 'active'; if (postgres) await pool.query("UPDATE actors SET status='active'");
  });
  const signed = async (changes = {}, header = {}) => {
    const now = Math.floor(Date.now()/1000);
    return new SignJWT({ iss: issuer, sub: actor.id, aud: audience, node_id: 'synthetic-node', jti: randomUUID(), iat: now, exp: now+25,
      scope: 'proxx:relay', allowed_models: ['test-model'], grant_revision: 'synthetic', ...changes })
      .setProtectedHeader({ alg: 'RS256', typ: 'axxium-relay+jwt', kid: 'synthetic-relay-key', ...header }).sign(privateKey);
  };
  await check('signed wrong issuer/audience/type/scope/node/owner/time/model refusals', async () => {
    const now = Math.floor(Date.now()/1000);
    for (const changes of [{ iss: 'https://other.example/oidc' }, { aud: 'https://other.example' }, { aud: [audience] }, { scope: 'openid' },
      { scope: 'proxx:relay admin' }, { node_id: 'unknown' }, { sub: 'other-actor' }, { sub: '' }, { sub: undefined }, { scope: undefined }, { node_id: undefined }, { exp: now-1 }, { exp: now+26 }, { iat: now+3 },
      { allowed_models: ['*'] }, { jti: '' }, { grant_revision: '' }]) await assert.rejects(auth.admit(`Bearer ${await signed(changes)}`));
    for (const typ of ['JWT', 'at+jwt']) await assert.rejects(auth.admit(`Bearer ${await signed({}, { typ })}`));
    await assert.rejects(auth.admit('Bearer malformed'));
    const other = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
    await assert.rejects(auth.admit(`Bearer ${await new SignJWT({}).setProtectedHeader({ alg: 'RS256', typ: 'axxium-relay+jwt', kid: 'synthetic-relay-key' }).sign(other)}`));
  });
  await check('concurrent one-use gate and store/broker reconstruction', async () => {
    const token = await lease();
    const results = await Promise.allSettled([auth.admit(`Bearer ${token}`), auth.admit(`Bearer ${token}`)]);
    assert.equal(results.filter(x => x.status === 'fulfilled').length, 1);
    if (postgres) {
      await pool.end();
      pool = new Pool({ host: root, user: 'relay_test', database: 'postgres', max: 8 });
    }
    store = new SqlFederationStore(sql); await assert.rejects(createBroker().admit(`Bearer ${token}`));
    if (!postgres) assert(queries.some(x => /ON CONFLICT \(issuer, jti\) DO NOTHING RETURNING jti/.test(x)));
    if (postgres) {
      await pool.query("INSERT INTO federation_relay_lease_uses VALUES('synthetic-old','old',clock_timestamp()-interval '2 minutes')");
      await createBroker().admit(`Bearer ${await lease()}`);
      assert.equal((await pool.query("SELECT * FROM federation_relay_lease_uses WHERE jti='old'")).rowCount, 0);
    }
  });
  setActiveCljsRuntime(runtime);
  const qualified = runtime.qualifiedOwner({ oidc: { issuer, subject: actor.id } });
  const identity = await auth.admit(`Bearer ${await lease()}`);
  await check('qualified user ownership ignores spoofed owner header', async () => {
    assert.equal(resolveFederationOwnerSubject({ headers: { 'x-open-hax-federation-owner-subject': 'other' }, requestAuth: { kind: 'ui_session', subject: 'product-local', oidc: { issuer, subject: actor.id } }, hopCount: 1 }), qualified);
    assert.equal(identity.relay.ownerSubject, qualified);
    const session = { authKind: 'axxium_node', ownerSubject: qualified };
    const user = { kind: 'ui_session', oidc: { issuer, subject: actor.id } };
    assert.equal(runtime.relaySessionRoutable(session, user, qualified, 'local-tenant'), true);
    assert.equal(runtime.relaySessionRoutable(session, { kind: 'legacy_admin' }, qualified, 'local-tenant'), false);
    assert.equal(runtime.relaySessionRoutable(session, { ...user, oidc: { issuer, subject: 'other' } }, qualified, 'local-tenant'), false);
  });
  await check('exact hello binding and bounded RPC/model contracts', async () => {
    const h = hello(qualified); identity.relay.assertHello(h);
    for (const bad of [{ ...h, agentId: 'other' }, { ...h, ownerSubject: actor.id }, { ...h, authMode: 'admin_key' }, { ...h, topology: { nodes: [{ nodeId: 'other' }] } }]) assert.throws(() => identity.relay.assertHello(bad));
    identity.relay.assertRequest({ method: 'POST', path: '/v1/responses' }, JSON.stringify({ model: 'test-model' }));
    for (const path of ['/v1/models?q=all', '/v1/responses/123', '/api/bridge/credentials/export', '/api/ui/settings', 'https://local/v1/models'])
      assert.throws(() => identity.relay.assertRequest({ method: 'GET', path }, ''));
    assert.throws(() => identity.relay.assertRequest({ method: 'POST', path: '/v1/embeddings' }, '{"model":"not-granted"}'));
    assert.throws(() => identity.relay.assertRequest({ method: 'POST', path: '/v1/embeddings' }, 'synthetic-private-body'), error => !String(error).includes('synthetic-private-body'));
    assert.throws(() => identity.relay.assertRequest({ method: 'POST', path: '/v1/responses' }, '{"model":"test-model","background":true}'));
    assert.deepEqual(identity.relay.headers({ authorization: 'local-secret', cookie: 'secret', 'x-open-hax-forced-provider': 'other', accept: 'application/json' }), { accept: 'application/json' });
  });
  await check('scoped lease cannot authenticate ordinary HTTP/account APIs', async () => {
    assert.equal(await resolveRequestAuth({ allowUnauthenticated: false, proxyAuthToken: 'synthetic-admin', authorization: `Bearer ${await lease()}` }), undefined);
  });
  broker = Fastify({ logger: false });
  const { bridgeRelay } = await registerWebSocketRoutes(broker, { config: { proxyAuthToken: 'synthetic-admin' },
    sqlFederationStore: store, requestLogStore: { subscribe: () => () => {} } });
  await broker.ready();
  const upgrade = async (token, message) => {
    const socket = new WireSocket(); socket.on('error', () => {});
    broker.server.emit('upgrade', { method: 'GET', url: '/api/ui/federation/bridge/ws',
      headers: { host: 'synthetic-broker.example', authorization: `Bearer ${token}`, upgrade: 'websocket', connection: 'Upgrade',
        'sec-websocket-version': '13', 'sec-websocket-key': randomBytes(16).toString('base64') } }, socket, Buffer.alloc(0));
    for (let i=0; i<100 && !socket.destroyed && !socket.chunks.length; i++) await wait(2);
    if (message && !socket.destroyed) { socket.frame(message); await wait(5); }
    return socket;
  };
  await check('actual upgrade adapter rejects mismatched hello', async () => {
    const socket = await upgrade(await lease(), { ...hello(qualified), agentId: 'other' });
    assert.equal(bridgeRelay.listSessions().filter(x => x.state === 'connected').length, 0);
    socket.destroy();
  });
  await check('actual upgrade expiry closes socket and fails ongoing delivery', async () => {
    const now = Math.floor(Date.now()/1000);
    const expiring = await auth.admit(`Bearer ${await signed({ iat: now, exp: now+2 })}`);
    const dormant = await upgrade(await signed({ iat: now, exp: now+2 }));
    const socket = await upgrade(await signed({ iat: now, exp: now+2 }), hello(qualified));
    const session = bridgeRelay.listSessions().find(x => x.state === 'connected'); assert(session);
    assert.throws(() => bridgeRelay.requestStream(session.sessionId, { method: 'GET', path: '/api/bridge/credentials/export', timeoutMs: 1000 }));
    const events = bridgeRelay.requestStream(session.sessionId, { method: 'POST', path: '/v1/responses', body: '{"model":"test-model"}', timeoutMs: 10000 });
    const pending = events[Symbol.asyncIterator]().next();
    await assert.rejects(pending, /closed during request/);
    assert(socket.destroyed); assert(dormant.destroyed); assert.equal(bridgeRelay.getSession(session.sessionId).state, 'disconnected');
    assert.throws(() => expiring.relay.assertActive());
  });
  await check('node agent fresh issuance, local-only handler, catalog narrowing and outage refusal', async () => {
    process.env.AXXIUM_RELAY_NODE_ISSUER = issuer; process.env.AXXIUM_RELAY_NODE_AUDIENCE = audience;
    process.env.AXXIUM_RELAY_NODE_ID = 'synthetic-node'; process.env.AXXIUM_RELAY_NODE_SUBJECT = actor.id;
    process.env.AXXIUM_RELAY_NODE_SECRET = nodeSecret;
    const options = runtime.relayNodeOptions({ relayUrl: `${audience.replace('https:', 'wss:')}/api/ui/federation/bridge/ws`, authorization: 'Bearer global-admin-must-never-be-used' });
    assert.equal(options.authorization, undefined); assert.equal(options.agentId, 'synthetic-node'); assert.equal(options.ownerSubject, qualified);
    const first = await options.prepareConnection(); const second = await options.prepareConnection(); assert.notEqual(first.authorization, second.authorization);
    let calls = 0;
    const handler = async input => { calls++; assert.equal(input.request.headers.authorization, undefined); assert.equal(input.request.requestContext, undefined); return { status: 200 }; };
    const base = { method: 'GET', path: '/v1/models', ownerSubject: qualified };
    const result = await first.relay.execute(base, '', handler, new AbortController().signal);
    assert.equal(calls, 0); assert.deepEqual(JSON.parse(result.body).data.map(x => x.id), ['test-model']);
    await first.relay.execute({ ...base, method: 'POST', path: '/v1/embeddings', requestContext: { tenantId: 'attacker' }, headers: { authorization: 'secret' } }, '{"model":"test-model"}', handler, new AbortController().signal);
    assert.equal(calls, 1);
    await assert.rejects(first.relay.execute({ ...base, path: '/api/bridge/credentials/export' }, '', handler, new AbortController().signal));
    const agent = createEnvFederationBridgeAgent({ config: {}, logger: { warn() {}, info() {} } });
    assert.equal(agent, undefined, 'no relay URL does not autostart');
    process.env.FEDERATION_BRIDGE_RELAY_URL = `${audience.replace('https:', 'wss:')}/api/ui/federation/bridge/ws`;
    const configuredAgent = createEnvFederationBridgeAgent({ config: {}, logger: { warn() {}, info() {} } });
    assert(configuredAgent, 'scoped autostart requires no legacy owner/DID/admin env');
    delete process.env.FEDERATION_BRIDGE_RELAY_URL;
    outage = true;
    await assert.rejects(options.prepareConnection());
    const client = createFederationBridgeAgent({ ...options, clusterId: 'synthetic', environment: 'test', bridgeAgentVersion: 'test', reconnectMinMs: 60000 });
    await client.start(); assert.notEqual(client.snapshot().state, 'connected'); assert.equal(client.snapshot().lastError.code, 'bridge_initial_connection_failed'); await client.stop();
    outage = false;
    config['node-grants'][0].enabled = false; writeConfig(); await assert.rejects(options.prepareConnection());
    config['node-grants'][0].enabled = true; writeConfig();
  });
  console.log(`PASS ${tests} relay adapter cases (${postgres ? 'real owned PostgreSQL' : 'SQL transport double; real PostgreSQL NOT verified'})`);
} finally {
  globalThis.fetch = originalFetch;
  for (const key of Object.keys(process.env)) if (!(key in oldEnv)) delete process.env[key];
  Object.assign(process.env, oldEnv);
  setActiveCljsRuntime(undefined);
  if (broker) await broker.close();
  if (authority) await authority.close();
  if (postgres && pool) await pool.end();
  if (started) pgRun('pg_ctl', ['-D', join(root, 'pg'), '-m', 'immediate', '-w', 'stop']);
  rmSync(root, { recursive: true, force: true });
}
