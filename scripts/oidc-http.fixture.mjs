import assert from 'node:assert/strict';
import { randomBytes, createHash, generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createServer } from 'node:net';
import Fastify from 'fastify';
import cookiePlugin from '@fastify/cookie';
import { runDeviceFixture } from './oidc-device.fixture.mjs';

const boundedFetch = (url, options = {}) => fetch(url, { ...options, signal: AbortSignal.timeout(10000) });

export async function runHttpFixture(pool, installHttp, root) {
  const probe = createServer();
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const origin = `http://127.0.0.1:${port}`;
  const actor = { id: 'http-fixture-actor', status: 'active', display_name: 'HTTP Fixture' };
  const otherActor = { id: 'http-fixture-other-actor', status: 'active', display_name: 'Other HTTP Fixture' };
  const otherRootToken = randomBytes(32).toString('base64url');
  const otherRootDigest = createHash('sha256').update(otherRootToken).digest('hex');
  const rootToken = randomBytes(32).toString('base64url');
  const rootDigest = createHash('sha256').update(rootToken).digest('hex');
  await pool.query("INSERT INTO actors VALUES($1,'active')", [actor.id]);
  await pool.query("INSERT INTO sessions VALUES($1,$2,clock_timestamp()+interval '1 hour')", [actor.id, rootDigest]);
  await pool.query("INSERT INTO actors VALUES($1,'active')", [otherActor.id]);
  await pool.query("INSERT INTO sessions VALUES($1,$2,clock_timestamp()+interval '1 hour')", [otherActor.id, otherRootDigest]);
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const client = { id: 'mounted-knoxx-fixture', secret: randomBytes(32).toString('hex'), 'redirect-uris': [`${origin}/callback`] };
  const other = { id: 'mounted-proxx-fixture', secret: randomBytes(32).toString('hex'), 'redirect-uris': [`${origin}/other-callback`] };
  const config = { issuer: `${origin}/oidc`, clients: [client, other], 'allow-loopback?': true, 'device-flow-enabled?': true,
    'cookie-keys': [randomBytes(32).toString('hex')],
    jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), kid: 'http-fixture', alg: 'RS256', use: 'sig' }] } };
  const configPath = join(root, 'oidc-http-fixture.json');
  writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
  const app = Fastify({ logger: false });
  app.register(cookiePlugin);
  app.post('/fixture/plainform', async () => ({ untouched: true }));
  try {
    await installHttp(app, pool, { 'config-file': configPath, 'public-base-url': origin, 'session-cookie-name': 'axxium_session' },
      async token => {
        const selected = token === rootToken ? [actor, rootDigest] : token === otherRootToken ? [otherActor, otherRootDigest] : null;
        return selected && (await pool.query('SELECT 1 FROM sessions WHERE token_hash=$1 AND expires_at>clock_timestamp()', [selected[1]])).rowCount === 1 ? selected[0] : null;
      },
      async subject => subject === actor.id ? actor : subject === otherActor.id ? otherActor : null);
    await app.listen({ port, host: '127.0.0.1' });
    for (const path of ['/oidc/unregistered-route', '/oidc/unregistered-route/nested']) {
      const missing = await boundedFetch(`${origin}${path}`);
      assert.equal(missing.status, 404, `${path}: ${await missing.text()}`);
    }
    const unrelated = await boundedFetch(`${origin}/fixture/plainform`, { method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'plain=value' });
    assert.equal(unrelated.status, 415, 'Interaction parser escaped its route plugin');
    const malformed = await boundedFetch(`${origin}/oidc/interaction/invalid`, { method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded', origin }, body: 'decision=approve' });
    assert.equal(malformed.status, 400);
    const discovery = await (await boundedFetch(`${origin}/oidc/.well-known/openid-configuration`)).json();
    assert.equal(discovery.issuer, config.issuer);
    assert.deepEqual(discovery.code_challenge_methods_supported, ['S256']);
    const verifier = randomBytes(32).toString('base64url');
    const nonce = randomBytes(24).toString('hex');
    const state = randomBytes(24).toString('hex');
    const start = `${discovery.authorization_endpoint}?${new URLSearchParams({ client_id: client.id,
      redirect_uri: client['redirect-uris'][0], response_type: 'code', scope: 'openid profile', nonce, state,
      code_challenge_method: 'S256', code_challenge: createHash('sha256').update(verifier).digest('base64url') })}`;
    const badRedirect = new URL(start);
    badRedirect.searchParams.set('redirect_uri', 'https://unregistered.invalid/callback');
    const rejected = await boundedFetch(badRedirect, { redirect: 'manual' });
    assert.equal(rejected.status, 400);
    assert.equal(rejected.headers.get('location'), null);
    const jar = new Map([['axxium_session', rootToken]]);
    let url = start;
    let checkedCsrf = false;
    let code;
    for (let i = 0; i < 20; i++) {
      if (new URL(url).pathname === '/callback') { code = new URL(url).searchParams.get('code'); assert.equal(new URL(url).searchParams.get('state'), state); break; }
      const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
      let response = await boundedFetch(url, { redirect: 'manual', headers: { cookie: cookie() } });
      const remember = r => {
        for (const item of r.headers.getSetCookie()) {
          const [key, ...value] = item.split(';')[0].split('=');
          if (value.join('=')) jar.set(key, value.join('=')); else jar.delete(key);
        }
      };
      remember(response);
      if (response.status === 200 && new URL(url).pathname.startsWith('/oidc/interaction/')) {
        const html = await response.text();
        const csrf = html.match(/name=csrf value='([^']+)'/)?.[1];
        assert.ok(csrf, html);
        assert.equal(response.headers.get('cache-control'), 'no-store');
        const submit = submittedOrigin => boundedFetch(url, { method: 'POST', redirect: 'manual',
          headers: { cookie: cookie(), origin: submittedOrigin, 'content-type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ csrf, decision: 'approve' }) });
        if (!checkedCsrf) { assert.equal((await submit('https://other-origin.invalid')).status, 403); checkedCsrf = true; }
        response = await submit(origin);
        remember(response);
      }
      const location = response.headers.get('location');
      assert.ok(location, `HTTP ${response.status}: ${await response.text()}`);
      url = new URL(location, origin).href;
    }
    assert.ok(code, 'Mounted authority did not issue an authorization code');
    const tokens = await boundedFetch(discovery.token_endpoint, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: client.id, client_secret: client.secret, grant_type: 'authorization_code', code,
        code_verifier: verifier, redirect_uri: client['redirect-uris'][0] }) });
    const body = await tokens.json();
    assert.equal(tokens.status, 200, JSON.stringify(body));
    const claims = JSON.parse(Buffer.from(body.id_token.split('.')[1], 'base64url'));
    assert.equal(claims.sub, actor.id); assert.equal(claims.aud, client.id); assert.equal(claims.nonce, nonce);
    const introspect = async () => (await (await boundedFetch(discovery.introspection_endpoint, { method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: client.id, client_secret: client.secret, token: body.access_token }) })).json()).active;
    assert.equal(await introspect(), true);
    await runDeviceFixture({ pool, discovery, origin, clients: [client, other], actor, rootToken, jar });
    await pool.query('DELETE FROM sessions WHERE token_hash=$1', [rootDigest]);
    assert.equal(await introspect(), false);
    // Keep oidc-provider's existing browser cookies while changing the signed-in
    // root actor, exactly as logout followed by a different account does.
    jar.set('axxium_session', otherRootToken);
    let switchedUrl = start, switchedCode;
    for (let i = 0; i < 20; i++) {
      if (new URL(switchedUrl).pathname === '/callback') { switchedCode = new URL(switchedUrl).searchParams.get('code'); break; }
      const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
      const remember = response => {
        for (const item of response.headers.getSetCookie()) {
          const [key, ...value] = item.split(';')[0].split('=');
          if (value.join('=')) jar.set(key, value.join('=')); else jar.delete(key);
        }
      };
      let response = await boundedFetch(switchedUrl, { redirect: 'manual', headers: { cookie: cookie() } }); remember(response);
      if (response.status === 200 && new URL(switchedUrl).pathname.startsWith('/oidc/interaction/')) {
        const html = await response.text();
        assert.ok(html.includes(otherActor.display_name), 'Consent must name the current root actor');
        const csrf = html.match(/name=csrf value='([^']+)'/)?.[1]; assert.ok(csrf);
        response = await boundedFetch(switchedUrl, { method: 'POST', redirect: 'manual',
          headers: { cookie: cookie(), origin, 'content-type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ csrf, decision: 'approve' }) }); remember(response);
      }
      if (response.status === 200) {
        // The maintained provider auto-submits its own session-switch logout
        // confirmation. Model the browser form, accepting only this exact route.
        const html = await response.text();
        const action = html.match(/<form method="post" action="([^"]+)"/)?.[1];
        assert.equal(action, `${origin}/oidc/session/end/confirm`, html);
        const fields = [...html.matchAll(/<input type="hidden" name="([^"]+)" value="([^"]*)"/g)];
        assert.ok(fields.some(field => field[1] === 'xsrf'));
        response = await boundedFetch(action, { method: 'POST', redirect: 'manual',
          headers: { cookie: cookie(), origin, 'content-type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams(fields.map(field => [field[1], field[2]])) }); remember(response);
      }
      const location = response.headers.get('location'); assert.ok(location, `Account switch HTTP ${response.status}: ${await response.text()}`);
      switchedUrl = new URL(location, origin).href;
    }
    assert.ok(switchedCode, 'Account switch must restart login and reach fresh consent');
    const switchedTokens = await boundedFetch(discovery.token_endpoint, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: client.id, client_secret: client.secret, grant_type: 'authorization_code', code: switchedCode,
        code_verifier: verifier, redirect_uri: client['redirect-uris'][0] }) });
    assert.equal(switchedTokens.status, 200);
    const switchedClaims = JSON.parse(Buffer.from((await switchedTokens.json()).id_token.split('.')[1], 'base64url'));
    assert.equal(switchedClaims.sub, otherActor.id);
    console.log('PASS mounted Fastify authority: discovery, root-session consent, Origin/CSRF refusal, token exchange and parent-session revocation');
  } finally {
    await app.close();
  }
}
