import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { generateKeyPairSync, randomBytes, createHash } from 'node:crypto';

// Synthetic interaction transport. It is never mounted by the product server.
const boundedFetch = (url, options = {}) => fetch(url, { ...options, signal: AbortSignal.timeout(10000) });

export async function runAuthorityFixture(pool, createProvider, bindSession, sessionGrantActive) {
  await pool.query("CREATE TABLE actors(id text PRIMARY KEY,status text NOT NULL); CREATE TABLE sessions(actor_id text,token_hash text,expires_at timestamptz); INSERT INTO actors VALUES('synthetic-actor','active'); INSERT INTO sessions VALUES('synthetic-actor','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',clock_timestamp()+interval '1 hour')");
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const secret = randomBytes(32).toString('hex');
  const fixtureApproval = randomBytes(32).toString('hex');
  let actorActive = true;
  let loginPrompts = 0;
  let provider;
  const server = createServer(async (req, res) => {
    try {
      if (req.url.startsWith('/oidc/interaction/')) {
        assert.equal(req.headers['x-fixture-approval'], fixtureApproval);
        const details = await provider.interactionDetails(req, res);
        let result;
        if (details.prompt.name === 'login') {
          loginPrompts++;
          result = { login: { accountId: 'synthetic-actor' } };
        } else if (details.prompt.name === 'consent') {
          const grant = new provider.Grant({ accountId: details.session.accountId, clientId: details.params.client_id });
          if (details.prompt.details.missingOIDCScope) grant.addOIDCScope(details.prompt.details.missingOIDCScope.join(' '));
          if (details.prompt.details.missingOIDCClaims) grant.addOIDCClaims(details.prompt.details.missingOIDCClaims);
          const grantId = await grant.save();
          await bindSession(pool, grantId, 'synthetic-actor', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
          result = { consent: { grantId } };
        } else throw new Error('Unsupported synthetic interaction');
        await provider.interactionFinished(req, res, result, { mergeWithLastSubmission: false });
      } else await provider.callback()(req, res);
    } catch {
      if (!res.headersSent) res.statusCode = 500;
      res.end('Synthetic fixture failure');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const issuer = `http://127.0.0.1:${server.address().port}`;
  const clients = ['knoxx-fixture', 'proxx-fixture'].map(id => ({ id, secret: randomBytes(32).toString('hex'), 'redirect-uris': [`${issuer}/callback/${id}`] }));
  const config = { issuer, clients, 'allow-loopback?': true, 'cookie-keys': [secret],
    jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), kid: 'synthetic', alg: 'RS256', use: 'sig' }] } };

  const jar = new Map();
  async function browser(start) {
    let url = start;
    for (let i = 0; i < 20; i++) {
      if (new URL(url).pathname.startsWith('/callback/')) return new URL(url);
      const response = await boundedFetch(url, { redirect: 'manual', headers: {
        cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), 'x-fixture-approval': fixtureApproval } });
      for (const cookie of response.headers.getSetCookie()) {
        const [key, ...value] = cookie.split(';')[0].split('=');
        if (value.join('=')) jar.set(key, value.join('=')); else jar.delete(key);
      }
      const target = response.headers.get('location');
      if (!target) return { status: response.status };
      url = new URL(target, issuer).href;
    }
    throw new Error('Synthetic interaction redirect loop');
  }
  async function form(path, data) {
    const response = await boundedFetch(issuer + path, { method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(data) });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }
  try {
  provider = createProvider(pool, config, async id => id === 'synthetic-actor'
    ? { id, status: actorActive ? 'active' : 'disabled', display_name: 'Synthetic Actor' } : null);
    const discovery = await (await boundedFetch(issuer + '/.well-known/openid-configuration')).json();
    assert.equal(discovery.issuer, issuer);
    assert(!discovery.grant_types_supported.includes('urn:ietf:params:oauth:grant-type:device_code'));
    for (const client of clients) {
      const verifier = randomBytes(32).toString('base64url');
      const state = randomBytes(24).toString('hex');
      const nonce = randomBytes(24).toString('hex');
      const auth = `${issuer}/auth?${new URLSearchParams({ client_id: client.id, redirect_uri: client['redirect-uris'][0],
        response_type: 'code', scope: 'openid profile', state, nonce,
        code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256' })}`;
      const callback = await browser(auth);
      assert(callback instanceof URL);
      assert.equal(callback.searchParams.get('state'), state);
      const request = { client_id: client.id, client_secret: client.secret, grant_type: 'authorization_code',
        code: callback.searchParams.get('code'), code_verifier: verifier, redirect_uri: client['redirect-uris'][0] };
      const tokens = await form('/token', request);
      assert.equal(tokens.status, 200, JSON.stringify(tokens.body));
      const claims = JSON.parse(Buffer.from(tokens.body.id_token.split('.')[1], 'base64url'));
      assert.equal(claims.sub, 'synthetic-actor'); assert.equal(claims.aud, client.id); assert.equal(claims.nonce, nonce);
      const statusRequest = { client_id: client.id, client_secret: client.secret, token: tokens.body.access_token };
      assert.equal((await form('/token/introspection', statusRequest)).body.active, true);
      const other = clients.find(x => x.id !== client.id);
      assert.equal((await form('/token/introspection', { ...statusRequest, client_id: other.id, client_secret: other.secret })).body.active, false);
      await form('/token/revocation', { ...statusRequest, client_id: other.id, client_secret: other.secret });
      assert.equal((await form('/token/introspection', statusRequest)).body.active, true);
      actorActive = false;
      assert.equal((await form('/token/introspection', statusRequest)).body.active, false);
      actorActive = true;
      await form('/token/revocation', statusRequest);
      assert.equal((await form('/token/introspection', statusRequest)).body.active, false);
      assert.equal((await form('/token', request)).status, 400);
      const raceCallback = await browser(auth);
      const raceRequest = { ...request, code: raceCallback.searchParams.get('code') };
      const exchanges = await Promise.all([form('/token', raceRequest), form('/token', raceRequest)]);
      assert.equal(exchanges.filter(x => x.status === 200).length, 1);
      assert.equal(exchanges.filter(x => x.status === 400).length, 1);
      // Replay detection may revoke the whole grant, including the first response.
      // It must never permit two successful redemption responses.
    }
    assert.equal(loginPrompts, 1);
    const rootClient = clients[0];
    const verifier = randomBytes(32).toString('base64url');
    const callback = await browser(`${issuer}/auth?${new URLSearchParams({
      client_id: rootClient.id, redirect_uri: rootClient['redirect-uris'][0], response_type: 'code',
      scope: 'openid profile', code_challenge_method: 'S256',
      code_challenge: createHash('sha256').update(verifier).digest('base64url'),
      state: randomBytes(24).toString('hex'), nonce: randomBytes(24).toString('hex') })}`);
    const fresh = await form('/token', { client_id: rootClient.id, client_secret: rootClient.secret,
      grant_type: 'authorization_code', code: callback.searchParams.get('code'),
      code_verifier: verifier, redirect_uri: rootClient['redirect-uris'][0] });
    assert.equal(fresh.status, 200);
    const rootStatus = { client_id: rootClient.id, client_secret: rootClient.secret, token: fresh.body.access_token };
    assert.equal((await form('/token/introspection', rootStatus)).body.active, true);
    await pool.query("UPDATE actors SET status='disabled' WHERE id='synthetic-actor'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, false);
    await pool.query("UPDATE actors SET status='active' WHERE id='synthetic-actor'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, true);
    await pool.query("DELETE FROM sessions WHERE token_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, false);
    const grants = await pool.query('SELECT grant_id FROM axxium_oidc_grant_sessions');
    for (const row of grants.rows) assert.equal(await sessionGrantActive(pool, row.grant_id, 'synthetic-actor'), false);
    console.log('PASS durable authority: shared synthetic login, PKCE, state/nonce/audience, code replay refusal, own-client introspection, disabled actor refusal and revocation');
  } finally {
    await new Promise(resolve => server.close(resolve));
    server.closeAllConnections();
  }
}
