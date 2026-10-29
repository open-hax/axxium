import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { generateKeyPairSync, randomBytes, createHash } from 'node:crypto';

// Synthetic interaction transport. It is never mounted by the product server.
const boundedFetch = (url, options = {}) => fetch(url, { ...options, signal: AbortSignal.timeout(10000) });

// Observe the public response contract before consuming token/discovery fields.
// A plaintext error must fail an assertion rather than escape as a parser error.
export async function readProtocolJSON(response, label, allowEmpty = false) {
  const text = await response.text();
  if (allowEmpty && text === '') return null;
  assert.match(response.headers.get('content-type') ?? '', /^application\/json(?:\s*;|$)/i,
    `${label} must declare a JSON response`);
  let value;
  assert.doesNotThrow(() => { value = JSON.parse(text); }, `${label} must contain valid JSON`);
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value),
    `${label} must contain a JSON object`);
  return value;
}

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
      // A failed synthetic handler/provider is an execution error, even when
      // its HTTP projection also violates an asserted response contract.
      // The accepted measurement preload verifies this fixture caller and
      // records an independent error counter; no exception payload is emitted.
      process.emit('axxiumQualificationFixtureError');
      if (!res.headersSent) res.statusCode = 500;
      res.end('Synthetic fixture failure');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const issuer = `http://127.0.0.1:${server.address().port}`;
  const clients = ['knoxx-fixture', 'proxx-fixture'].map(id => ({ id, secret: randomBytes(32).toString('hex'), 'redirect-uris': [`${issuer}/callback/${id}`], 'refresh-enabled?': true }));
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
    // RFC7009 successful revocation permits an empty200 body; other protocol
    // responses, including revocation errors, must remain JSON objects.
    const allowEmpty = path === '/token/revocation' && response.status === 200;
    return { status: response.status,
      body: await readProtocolJSON(response, 'Authority form response', allowEmpty) };
  }
  // The protocol engine can consume a rejected database promise and render a
  // 500 internally. Observe this owned pool during the fixture so the original
  // query rejection remains an error independently of its HTTP projection.
  const originalQuery = pool.query;
  pool.query = function (...args) {
    try {
      const result = Reflect.apply(originalQuery, this, args);
      return result && typeof result.then === 'function' ? result.catch(error => {
        process.emit('axxiumQualificationFixtureError'); throw error;
      }) : result;
    } catch (error) {
      process.emit('axxiumQualificationFixtureError'); throw error;
    }
  };
  try {
  provider = createProvider(pool, config, async id => id === 'synthetic-actor'
    ? { id, status: actorActive ? 'active' : 'disabled', display_name: 'Synthetic Actor' } : null);
    // The engine handles some rejected queries internally before callback()
    // rejects. Observe its public error event as well as our outer handler.
    provider.on('server_error', () => process.emit('axxiumQualificationFixtureError'));
    const discovery = await readProtocolJSON(
      await boundedFetch(issuer + '/.well-known/openid-configuration'), 'Authority discovery');
    assert.equal(discovery.issuer, issuer);
    assert.ok(!discovery.grant_types_supported.includes('urn:ietf:params:oauth:grant-type:device_code'));
    for (const client of clients) {
      const verifier = randomBytes(32).toString('base64url');
      const state = randomBytes(24).toString('hex');
      const nonce = randomBytes(24).toString('hex');
      const auth = `${issuer}/auth?${new URLSearchParams({ client_id: client.id, redirect_uri: client['redirect-uris'][0],
        response_type: 'code', scope: 'openid profile', state, nonce,
        code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256' })}`;
      const callback = await browser(auth);
      assert.ok(callback instanceof URL);
      assert.equal(callback.searchParams.get('state'), state);
      const request = { client_id: client.id, client_secret: client.secret, grant_type: 'authorization_code',
        code: callback.searchParams.get('code'), code_verifier: verifier, redirect_uri: client['redirect-uris'][0] };
      const tokens = await form('/token', request);
      assert.equal(tokens.status, 200, JSON.stringify(tokens.body));
      assert.equal(typeof tokens.body.refresh_token, 'string');
      const refreshRequest = {client_id: client.id, client_secret: client.secret,
        grant_type: 'refresh_token', refresh_token: tokens.body.refresh_token};
      const otherRefreshClient = clients.find(x => x.id !== client.id);
      assert.equal((await form('/token', {...refreshRequest, client_id: otherRefreshClient.id,
        client_secret: otherRefreshClient.secret})).status, 400);
      const renewed = await form('/token', refreshRequest);
      assert.equal(renewed.status, 200, JSON.stringify(renewed.body));
      assert.notEqual(renewed.body.refresh_token, tokens.body.refresh_token);
      const renewedClaims = JSON.parse(Buffer.from(renewed.body.id_token.split('.')[1], 'base64url'));
      assert.equal(renewedClaims.sub, 'synthetic-actor');
      assert.equal(renewedClaims.nonce, nonce);
      actorActive = false;
      assert.equal((await form('/token', {...refreshRequest, refresh_token: renewed.body.refresh_token})).status, 400,
        'refresh pipeline must invoke current findAccount before consuming or issuing');
      actorActive = true;

      const savedRefresh = await pool.query("SELECT payload,expires_at FROM axxium_oidc_records WHERE model='RefreshToken' AND consumed_at IS NULL AND payload->>'clientId'=$1", [client.id]);
      assert.ok(savedRefresh.rows.length > 0);
      const bound = await pool.query("SELECT LEAST(b.expires_at,s.expires_at) AS expiry FROM axxium_oidc_grant_sessions b JOIN sessions s ON s.token_hash=b.token_hash WHERE b.grant_id=$1", [savedRefresh.rows[0].payload.grantId]);
      assert.ok(savedRefresh.rows[0].expires_at <= bound.rows[0].expiry);
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
      const rotationRequest = {...refreshRequest, refresh_token: renewed.body.refresh_token};
      const rotationRace = await Promise.all([form('/token', rotationRequest), form('/token', rotationRequest)]);
      assert.ok(rotationRace.filter(x => x.status === 200).length <= 1);
      assert.ok(rotationRace.some(x => x.status === 400));
      assert.equal((await form('/token', rotationRequest)).status, 400);
      assert.equal((await form('/token', request)).status, 400);

      const raceCallback = await browser(auth);
      const raceRequest = { ...request, code: raceCallback.searchParams.get('code') };
      const exchanges = await Promise.all([form('/token', raceRequest), form('/token', raceRequest)]);
      assert.ok(exchanges.filter(x => x.status === 200).length <= 1);
      assert.ok(exchanges.some(x => x.status === 400));
      const raceWinner = exchanges.find(x => x.status === 200);
      if (raceWinner?.body.refresh_token) {
        const req = {...refreshRequest, refresh_token: raceWinner.body.refresh_token};
        const rotations = await Promise.all([form('/token', req), form('/token', req)]);
        assert.ok(rotations.filter(x => x.status === 200).length <= 1);
        assert.ok(rotations.some(x => x.status === 400));
        assert.equal((await form('/token', req)).status, 400);
      }
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
    const rootRefresh = {client_id: rootClient.id, client_secret: rootClient.secret, grant_type: 'refresh_token', refresh_token: fresh.body.refresh_token};
    const rootStatus = { client_id: rootClient.id, client_secret: rootClient.secret, token: fresh.body.access_token };
    // A separate engine Grant record must remain present as well as its parent binding.
    // Restore the byte-identical fixture row only to continue independent refusal cases.
    const persisted = await pool.query("SELECT id,payload,expires_at FROM axxium_oidc_records WHERE model='RefreshToken' AND id=$1", [fresh.body.refresh_token]);
    assert.equal(persisted.rows.length, 1);
    const grantId = persisted.rows[0].payload.grantId;
    const savedGrant = await pool.query("DELETE FROM axxium_oidc_records WHERE model='Grant' AND id=$1 RETURNING id,payload,expires_at", [grantId]);
    assert.equal(savedGrant.rows.length, 1);
    assert.equal((await form('/token', rootRefresh)).status, 400, 'loss of engine grant refuses refresh');
    await pool.query("INSERT INTO axxium_oidc_records(model,id,payload,expires_at) VALUES('Grant',$1,$2,$3)",
      [savedGrant.rows[0].id, savedGrant.rows[0].payload, savedGrant.rows[0].expires_at]);
    const parentRows = await pool.query('SELECT expires_at FROM axxium_oidc_grant_sessions WHERE grant_id=$1', [grantId]);
    await pool.query("UPDATE axxium_oidc_grant_sessions SET expires_at=clock_timestamp()+interval '30 seconds' WHERE grant_id=$1", [grantId]);
    const shortened = await form('/token', rootRefresh);
    assert.equal(shortened.status, 200, JSON.stringify(shortened.body));
    rootRefresh.refresh_token = shortened.body.refresh_token;
    const boundedRow = await pool.query("SELECT r.expires_at<=b.expires_at AS bounded FROM axxium_oidc_records r JOIN axxium_oidc_grant_sessions b ON b.grant_id=r.payload->>'grantId' WHERE r.model='RefreshToken' AND r.id=$1", [shortened.body.refresh_token]);
    assert.equal(boundedRow.rows[0]?.bounded, true, 'rotation uses current shortened parent expiry');
    await pool.query("UPDATE axxium_oidc_grant_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE grant_id=$1", [grantId]);
    assert.equal((await form('/token', rootRefresh)).status, 400, 'current stored parent expiry refuses refresh');
    await pool.query('UPDATE axxium_oidc_grant_sessions SET expires_at=$2 WHERE grant_id=$1', [grantId,parentRows.rows[0].expires_at]);

    assert.equal((await form('/token/introspection', rootStatus)).body.active, true);
    await pool.query("UPDATE actors SET status='disabled' WHERE id='synthetic-actor'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, false);
    assert.equal((await form('/token', rootRefresh)).status, 400, 'findAccount must refuse a disabled actor during refresh');
    await pool.query("UPDATE actors SET status='active' WHERE id='synthetic-actor'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, true);
    await pool.query("DELETE FROM sessions WHERE token_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'");
    assert.equal((await form('/token/introspection', rootStatus)).body.active, false);
    assert.equal((await form('/token', rootRefresh)).status, 400, 'refresh cannot outlive root logout');
    const grants = await pool.query('SELECT grant_id FROM axxium_oidc_grant_sessions');
    for (const row of grants.rows) assert.equal(await sessionGrantActive(pool, row.grant_id, 'synthetic-actor'), false);
    console.log('PASS durable authority: shared synthetic login, PKCE, state/nonce/audience, code replay refusal, own-client introspection, disabled actor refusal and revocation');
  } finally {
    pool.query = originalQuery;
    await new Promise(resolve => server.close(resolve));
    server.closeAllConnections();
  }
}
