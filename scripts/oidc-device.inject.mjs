import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Fastify from 'fastify';
import cookiePlugin from '@fastify/cookie';
import { installHttp } from '../target/oidc-store/store.js';
// No network listener or database: exercise the real pinned provider and Fastify
// mount with a deliberately small SQL stub. Atomic/durable PG behavior belongs
// to oidc-store.integration.mjs, not to this smoke test. All credentials are synthetic.
const records = new Map(),
  bindings = new Map();
let rootActive = true;
const key = (m, id) => `${m}:${id}`;
const clone = (x) => structuredClone(x);
const pool = {
  async connect() {
    return { query: pool.query, release() {} };
  },
  async query(sql, p = []) {
    let rows = [],
      rowCount = 0;
    if (/^(CREATE|ALTER|BEGIN|COMMIT|ROLLBACK)/.test(sql)) return { rows, rowCount };
    if (sql.startsWith('WITH expired AS')) return { rows, rowCount }; // Retention is qualified against real PostgreSQL.
    if (sql.startsWith('INSERT INTO axxium_oidc_records')) {
      if (sql.includes('JOIN axxium_oidc_grant_sessions') && (!rootActive || bindings.get(p[4]) !== p[5] || !records.has(key('Grant', p[4])))) return {rows, rowCount};
      const k = key(p[0], p[1]),
        prior = records.get(k);
      records.set(k, {
        payload: JSON.parse(p[2]),
        consumed_at: prior?.consumed_at ?? null,
        poll_last_ms: prior?.poll_last_ms ?? null,
        poll_count: prior?.poll_count ?? 0,
        poll_interval: prior?.poll_interval ?? 5,
        expiry_ms:
          prior && p[0] === 'DeviceCode'
            ? Math.min(prior.expiry_ms, Date.now() + p[3] * 1000)
            : Date.now() + p[3] * 1000,
      });
      rowCount = 1;
    } else if (sql.startsWith('INSERT INTO axxium_oidc_grant_sessions')) {
      if (rootActive && !bindings.has(p[0])) {
        bindings.set(p[0], p[1]);
        rowCount = 1;
      }
    } else if (sql.startsWith('SELECT floor(extract(epoch from LEAST')) {
      if (rootActive && bindings.get(p[0]) === p[1]) { rows = [{expires_ms: Date.now() + 3600000}]; rowCount = 1; }
    } else if (sql.startsWith('SELECT EXISTS')) {
      rows = [{ active: rootActive && bindings.get(p[0]) === p[1] }];
      rowCount = 1;
    } else if (sql.startsWith('SELECT')) {
      let row;
      if (sql.includes("model='DeviceCode'")) row = records.get(key('DeviceCode', p[0]));
      else if (sql.includes("payload->>'userCode'=$2"))
        row = [...records].find(([k, r]) => k.startsWith(`${p[0]}:`) && r.payload.userCode === p[1])?.[1];
      else if (sql.includes("payload->>'uid'=$2"))
        row = [...records].find(([k, r]) => k.startsWith(`${p[0]}:`) && r.payload.uid === p[1])?.[1];
      else row = records.get(key(p[0], p[1]));
      if (row && (!sql.includes('expires_at>clock_timestamp()') || row.expiry_ms > Date.now())) {
        rows = [{ ...clone(row), now_ms: Date.now() }];
        rowCount = 1;
      }
    } else if (sql.startsWith('UPDATE axxium_oidc_records SET poll_last_ms')) {
      Object.assign(records.get(key('DeviceCode', p[0])), {
        poll_last_ms: p[1],
        poll_interval: p[2],
        poll_count: p[3],
      });
      rowCount = 1;
    } else if (sql.startsWith('UPDATE axxium_oidc_records SET consumed_at')) {
      const r = records.get(key(p[0], p[1]));
      if (r && !r.consumed_at && r.expiry_ms > Date.now()) {
        r.consumed_at = Math.floor(Date.now() / 1000);
        rowCount = 1;
      }
    } else if (sql.startsWith('DELETE FROM axxium_oidc_records')) {
      for (const [k, r] of records) {
        if (sql.includes("payload->>'grantId'=$1") ? (r.payload.grantId === p[0] || k === key('Grant', p[0])) : k === key(p[0], p[1])) {
          records.delete(k);
          rowCount++;
        }
      }
    } else throw Error(`Unimplemented test query: ${sql}`);
    return { rows, rowCount };
  },
};
const origin = 'http://127.0.0.1:44555',
  actor = { id: 'injected-actor', status: 'active', display_name: 'Injected Actor' },
  rootToken = randomBytes(32).toString('hex');
const clients = ['device-smoke', 'other-smoke'].map((id) => ({
  id,
  secret: randomBytes(32).toString('hex'),
  'redirect-uris': [`${origin}/callback`],
  'refresh-enabled?': true,
}));
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const root = mkdtempSync(join(tmpdir(), 'axxium-device-inject-'));
const path = join(root, 'private-config.json');
writeFileSync(
  path,
  JSON.stringify({
    issuer: `${origin}/oidc`,
    clients,
    'allow-loopback?': true,
    'device-flow-enabled?': true,
    'cookie-keys': [randomBytes(32).toString('hex')],
    jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), kid: 'smoke', alg: 'RS256', use: 'sig' }] },
  }),
  { mode: 0o600 },
);
const app = Fastify({ logger: false });
app.register(cookiePlugin);
const jar = new Map([['axxium_session', rootToken]]);
async function request(url, data, browser = false, changes = {}) {
  const u = new URL(url, origin),
    headers = {
      host: u.host,
      ...(data ? { 'content-type': 'application/x-www-form-urlencoded' } : {}),
      ...(browser
        ? { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), ...(data ? { origin } : {}) }
        : {}),
      ...changes,
    };
  const r = await app.inject({
    method: data ? 'POST' : 'GET',
    url: u.pathname + u.search,
    headers,
    ...(data ? { payload: new URLSearchParams(data).toString() } : {}),
  });
  for (const item of [].concat(r.headers['set-cookie'] || [])) {
    const [k, ...v] = item.split(';')[0].split('=');
    if (v.join('=')) jar.set(k, v.join('='));
    else jar.delete(k);
  }
  return r;
}
try {
  await installHttp(
    app,
    pool,
    { 'config-file': path, 'public-base-url': origin, 'session-cookie-name': 'axxium_session' },
    async (t) => (rootActive && t === rootToken ? actor : null),
    async (id) => (id === actor.id ? actor : null),
  );
  await app.ready();
  const discovery = (await request('/oidc/.well-known/openid-configuration')).json();
  const client = clients[0],
    grant = 'urn:ietf:params:oauth:grant-type:device_code';
  const issue = await request(discovery.device_authorization_endpoint, {
    client_id: client.id,
    client_secret: client.secret,
    scope: 'openid profile',
  });
  assert.equal(issue.statusCode, 200, issue.body);
  const device = issue.json();
  assert.equal(device.interval, 5);
  const poll = (owner = client, changes = {}) =>
    request(discovery.token_endpoint, {
      client_id: owner.id,
      client_secret: owner.secret,
      grant_type: grant,
      device_code: device.device_code,
      ...changes,
    });
  assert.equal((await poll(clients[1])).json().error, 'invalid_grant');
  assert.equal((await poll(client, { client_secret: 'invalid' })).json().error, 'invalid_client');
  assert.equal((await poll()).json().error, 'authorization_pending');
  assert.equal((await poll()).json().error, 'slow_down');
  assert.equal((await request(device.verification_uri)).statusCode, 401);
  const approve = async (device, decision = 'approve') => {
    let r = await request(device.verification_uri, null, true);
    const hidden = (s, n) => s.match(new RegExp(`name="${n}" value="([^"]+)"`))?.[1];
    let data = { xsrf: hidden(r.body, 'xsrf'), user_code: device.user_code };
    assert(data.xsrf, r.body);
    assert.equal(
      (await request(device.verification_uri, data, true, { origin: 'https://evil.invalid' })).statusCode,
      403,
    );
    r = await request(device.verification_uri, data, true);
    assert.equal(r.statusCode, 200, r.body);
    data = { xsrf: hidden(r.body, 'xsrf'), user_code: hidden(r.body, 'user_code'), confirm: 'yes' };
    r = await request(device.verification_uri, data, true);
    let prompts = 0;
    while (r.headers.location) {
      const url = r.headers.location;
      r = await request(url, null, true);
      if (new URL(url, origin).pathname.startsWith('/oidc/interaction/') && r.statusCode === 200) {
        const csrf = r.body.match(/name=csrf value='([^']+)'/)?.[1];
        assert(csrf, r.body);
        assert.equal(
          (await request(url, { csrf, decision: 'approve' }, true, { origin: 'https://evil.invalid' }))
            .statusCode,
          403,
        );
        const uid = new URL(url, origin).pathname.split('/').at(-1);
        const prompt = records.get(key('Interaction', uid))?.payload.prompt.name;
        assert.equal(
          (await request(url, { csrf: 'x'.repeat(43), decision: 'approve' }, true)).statusCode,
          403,
        );
        r = await request(url, { csrf, decision: prompt === 'consent' ? decision : 'approve' }, true);
        prompts++;
      }
      if (prompts > 4) throw Error('interaction loop');
    }
    assert.equal(r.statusCode, 200, r.body);
    assert(
      r.body.includes(decision === 'approve' ? 'Device approved' : 'Device request could not be completed.'),
      r.body,
    );
    return prompts;
  };
  assert.equal(await approve(device), 2);
  const record = records.get(key('DeviceCode', device.device_code));
  record.poll_last_ms -= record.poll_interval * 1000;
  const tokens = await poll();
  assert.equal(tokens.statusCode, 200, tokens.body);
  assert.equal((await poll()).json().error, 'invalid_grant');
  const introspect = () =>
    request(discovery.introspection_endpoint, {
      client_id: client.id,
      client_secret: client.secret,
      token: tokens.json().access_token,
    });
  assert.equal((await introspect()).json().active, true);
  assert.equal(typeof tokens.json().refresh_token, 'string');
  assert.equal(typeof (await introspect()).json().root_session_expires_at, 'number');
  const refresh = (token, registered = client) => request(discovery.token_endpoint, {
    client_id: registered.id, client_secret: registered.secret,
    grant_type: 'refresh_token', refresh_token: token,
  });
  assert.equal((await refresh(tokens.json().refresh_token, clients[1])).statusCode, 400);
  const rotated = await refresh(tokens.json().refresh_token);
  assert.equal(rotated.statusCode, 200, rotated.body);
  assert.notEqual(rotated.json().refresh_token, tokens.json().refresh_token);
  assert.equal(JSON.parse(Buffer.from(rotated.json().id_token.split('.')[1], 'base64url')).sub, actor.id);
  actor.status = 'disabled';
  assert.equal((await refresh(rotated.json().refresh_token)).statusCode, 400, 'findAccount refuses a disabled actor before rotation');
  actor.status = 'active';
  rootActive = false;
  assert.equal((await refresh(rotated.json().refresh_token)).statusCode, 400, 'current parent binding is mandatory before rotation');
  rootActive = true;
  const races = await Promise.all([refresh(rotated.json().refresh_token), refresh(rotated.json().refresh_token)]);
  assert(races.filter(r => r.statusCode === 200).length <= 1);
  assert(races.some(r => r.statusCode === 400));
  assert.equal((await refresh(rotated.json().refresh_token)).statusCode, 400, 'one-use replay refuses');


  const another = async () =>
    (
      await request(discovery.device_authorization_endpoint, {
        client_id: client.id,
        client_secret: client.secret,
        scope: 'openid profile',
      })
    ).json();
  const denied = await another();
  assert.equal(await approve(denied, 'deny'), 1, 'An existing OIDC session still requires fresh consent');
  const pollAnother = (d) =>
    request(discovery.token_endpoint, {
      client_id: client.id,
      client_secret: client.secret,
      grant_type: grant,
      device_code: d.device_code,
    });
  assert.equal((await pollAnother(denied)).json().error, 'access_denied');
  assert.equal((await pollAnother(denied)).json().error, 'invalid_grant');
  const expired = await another();
  records.get(key('DeviceCode', expired.device_code)).expiry_ms = Date.now() - 1;
  assert.equal((await pollAnother(expired)).json().error, 'expired_token');
  const waiting = await another();
  assert.equal(await approve(waiting), 1);
  rootActive = false;
  assert.equal((await introspect()).json().active, false);
  assert.equal((await pollAnother(waiting)).json().error, 'invalid_grant');
  assert.equal((await refresh(tokens.json().refresh_token)).statusCode, 400, 'root logout prevents refresh');
  for (const record of records.values()) {
    const text = JSON.stringify(record.payload);
    assert(!text.includes(rootToken));
    for (const registered of clients) assert(!text.includes(registered.secret));
  }
  console.log(
    'PASS no-bind mounted RFC8628 and root-bound rotating refresh (mock SQL): wrong-client, actor/parent refusal, refresh concurrency/replay, authenticated polling, pending/backoff, root/Origin/CSRF approval, fresh consent, denial/replay, expiry and root revocation. PostgreSQL semantics require test:oidc-store.',
  );
} finally {
  await app.close();
  rmSync(root, { recursive: true, force: true });
}
