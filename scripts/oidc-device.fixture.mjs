import assert from 'node:assert/strict';
import { safeInteractionReturn } from '../resources/public/oidc-return.js';
import { readProtocolJSON } from './oidc-protocol.fixture.mjs';

// Synthetic device approval over the real mounted transport and owned fixture PG.
// The clock is advanced in owned rows; no sleeps or service credentials are used.
const deviceGrant = 'urn:ietf:params:oauth:grant-type:device_code';
const boundedFetch = (url, options = {}) => fetch(url, { ...options, signal: AbortSignal.timeout(10000) });

export async function runDeviceFixture({ pool, discovery, origin, clients, actor, rootToken, jar }) {
  const [client, other] = clients;
  const form = async (url, data) => {
    const response = await boundedFetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(data),
    });
    return { status: response.status, body: await readProtocolJSON(response, 'Device form response') };
  };
  const issue = async (owner = client) => {
    const result = await form(discovery.device_authorization_endpoint, {
      client_id: owner.id,
      client_secret: owner.secret,
      scope: 'openid profile',
    });
    assert.equal(result.status, 200);
    assert.equal(result.body.expires_in, 600);
    assert.equal(result.body.interval, 5);
    assert.equal(new URL(result.body.verification_uri).origin, origin);
    return result.body;
  };
  const poll = (device, owner = client, changes = {}) =>
    form(discovery.token_endpoint, {
      client_id: owner.id,
      client_secret: owner.secret,
      grant_type: deviceGrant,
      device_code: device.device_code,
      ...changes,
    });
  const evidence = async (device) =>
    (
      await pool.query(
        "SELECT poll_last_ms,poll_interval,poll_count,consumed_at FROM axxium_oidc_records WHERE model='DeviceCode' AND id=$1",
        [device.device_code],
      )
    ).rows[0];
  const advance = (device) =>
    pool.query(
      "UPDATE axxium_oidc_records SET poll_last_ms=poll_last_ms-(poll_interval*1000) WHERE model='DeviceCode' AND id=$1",
      [device.device_code],
    );
  const remember = (response) => {
    for (const item of response.headers.getSetCookie()) {
      const [key, ...value] = item.split(';')[0].split('=');
      if (value.join('=')) jar.set(key, value.join('='));
      else jar.delete(key);
    }
  };
  const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const browse = async (url, data, changes = {}) => {
    const response = await boundedFetch(url, {
      redirect: 'manual',
      method: data ? 'POST' : 'GET',
      headers: {
        cookie: cookie(),
        ...(data ? { origin, 'content-type': 'application/x-www-form-urlencoded' } : {}),
        ...changes,
      },
      ...(data ? { body: new URLSearchParams(data) } : {}),
    });
    remember(response);
    return response;
  };
  const hidden = (html, name) => html.match(new RegExp(`name="${name}" value="([^"]+)"`))?.[1];
  let consentCount = 0;
  async function approve(device, decision = 'approve') {
    let input = await browse(device.verification_uri);
    assert.equal(input.status, 200);
    assert.equal(
      (await browse(device.verification_uri, { xsrf: 'invalid', user_code: device.user_code })).status,
      400,
    );
    input = await browse(device.verification_uri);
    const xsrf = hidden(await input.text(), 'xsrf');
    assert.ok(xsrf);
    // Exact Origin + engine session XSRF + real root session all protect writes.
    const request = { xsrf, user_code: device.user_code };
    assert.equal(
      (await browse(device.verification_uri, request, { origin: 'https://cross.invalid' })).status,
      403,
    );
    assert.equal((await browse(device.verification_uri, request, { cookie: '' })).status, 401);
    const confirm = await browse(device.verification_uri, request);
    assert.equal(confirm.status, 200);
    const confirmHtml = await confirm.text();
    assert.ok(confirmHtml.includes(device.user_code));
    let response = await browse(device.verification_uri, {
      xsrf: hidden(confirmHtml, 'xsrf'),
      user_code: hidden(confirmHtml, 'user_code'),
      confirm: 'yes',
    });
    let consentSeen = false;
    for (let step = 0; step < 15; step++) {
      const location = response.headers.get('location');
      if (!location) {
        assert.equal(response.status, 200);
        const html = await response.text();
        assert.ok(
          consentSeen,
          'Every device needs explicit consent, including an existing shared browser grant',
        );
        assert.ok(
          html.includes(
            decision === 'approve' ? 'Device approved' : 'Device request could not be completed.',
          ),
        );
        return;
      }
      const url = new URL(location, origin).href;
      response = await browse(url);
      if (response.status === 200 && new URL(url).pathname.startsWith('/oidc/interaction/')) {
        const html = await response.text();
        const csrf = html.match(/name=csrf value='([^']+)'/)?.[1];
        assert.ok(csrf);
        assert.ok(!html.includes(rootToken));
        assert.ok(!html.includes(client.secret));
        assert.equal(
          (await browse(url, { csrf, decision }, { origin: 'https://cross.invalid' })).status,
          403,
        );
        assert.equal((await browse(url, { csrf: 'x'.repeat(43), decision })).status, 403);
        assert.equal(
          (
            await browse(
              url,
              { csrf, decision },
              {
                cookie: cookie()
                  .split('; ')
                  .filter((x) => !x.startsWith('axxium_session='))
                  .join('; '),
              },
            )
          ).status,
          401,
        );
        // The first device uses an existing OIDC login from code+PKCE. A fresh
        // browser may also have a login prompt before the mandatory consent.
        if (html.includes('Allow sign-in')) {
          // Determine consent from its persisted interaction (never trust HTML labels).
          const uid = new URL(url).pathname.split('/').at(-1);
          const row = await pool.query(
            "SELECT payload FROM axxium_oidc_records WHERE model='Interaction' AND id=$1",
            [uid],
          );
          if (row.rows[0]?.payload.prompt?.name === 'consent') {
            consentSeen = true;
            consentCount++;
          }
        }
        response = await browse(url, { csrf, decision: consentSeen ? decision : 'approve' });
      }
    }
    throw new Error('Device interaction exceeded its redirect bound');
  }

  assert.ok(discovery.grant_types_supported.includes(deviceGrant));
  const unsignedDevice = await boundedFetch(`${origin}/oidc/device`, { redirect: 'manual' });
  assert.equal(unsignedDevice.status, 302);
  const returnTarget = new URL(unsignedDevice.headers.get('location'), origin).searchParams.get('oidc_return');
  assert.equal(safeInteractionReturn(returnTarget), '/oidc/device');
  const device = await issue();
  const unsignedComplete = await boundedFetch(device.verification_uri_complete, { redirect: 'manual' });
  assert.equal(unsignedComplete.status, 302);
  const completeTarget = new URL(unsignedComplete.headers.get('location'), origin).searchParams.get('oidc_return');
  assert.equal(safeInteractionReturn(completeTarget), `/oidc/device?user_code=${device.user_code}`);
  assert.deepEqual(await evidence(device), {
    poll_last_ms: null,
    poll_interval: 5,
    poll_count: 0,
    consumed_at: null,
  });
  const before = await evidence(device);
  assert.equal((await poll(device, other)).body.error, 'invalid_grant');
  assert.equal((await poll(device, client, { client_secret: 'wrong-secret' })).body.error, 'invalid_client');
  assert.equal((await poll(device, client, { client_id: other.id })).body.error, 'invalid_client');
  assert.deepEqual(
    await evidence(device),
    before,
    'Wrong or unauthenticated client cannot mutate polling evidence',
  );
  assert.equal((await poll({ device_code: 'unknown-device-code' })).body.error, 'invalid_grant');
  // Requests deliberately have no browser Origin, cookies or CSRF fields.
  assert.equal((await poll(device)).body.error, 'authorization_pending');
  assert.equal((await poll(device)).body.error, 'slow_down');
  assert.equal((await evidence(device)).poll_interval, 10);
  assert.equal((await poll(device)).body.error, 'slow_down');
  assert.equal((await evidence(device)).poll_interval, 15);
  assert.equal((await evidence(device)).poll_count, 3);
  const browserEvidence = await evidence(device);
  await approve(device);
  assert.deepEqual(await evidence(device), browserEvidence, 'Browser verification does not count as polling');
  await advance(device);
  const tokens = await poll(device);
  assert.equal(tokens.status, 200);
  const claims = JSON.parse(Buffer.from(tokens.body.id_token.split('.')[1], 'base64url'));
  assert.equal(claims.sub, actor.id);
  assert.equal(claims.aud, client.id);
  const status = async (token = tokens.body.access_token, owner = client) =>
    (
      await form(discovery.introspection_endpoint, {
        client_id: owner.id,
        client_secret: owner.secret,
        token,
      })
    ).body.active;
  assert.equal(await status(), true);
  const userinfo = await boundedFetch(discovery.userinfo_endpoint, {
    headers: { authorization: `Bearer ${tokens.body.access_token}` },
  });
  assert.equal(userinfo.status, 200);
  assert.equal((await readProtocolJSON(userinfo, 'Device userinfo response')).sub, actor.id);
  assert.equal(await status(tokens.body.access_token, other), false);
  assert.equal((await poll(device)).body.error, 'invalid_grant');

  const denied = await issue();
  await approve(denied, 'deny');
  assert.equal((await poll(denied)).body.error, 'access_denied');
  assert.equal((await poll(denied)).body.error, 'invalid_grant');

  const expired = await issue();
  await pool.query(
    "UPDATE axxium_oidc_records SET expires_at=clock_timestamp()-interval '1 second',payload=jsonb_set(payload,'{exp}',to_jsonb(floor(extract(epoch from clock_timestamp()))::bigint-1)) WHERE model='DeviceCode' AND id=$1",
    [expired.device_code],
  );
  assert.equal((await poll(expired)).body.error, 'expired_token');
  assert.equal((await evidence(expired)).poll_count, 0);

  const capped = await issue();
  await pool.query("UPDATE axxium_oidc_records SET poll_count=120 WHERE model='DeviceCode' AND id=$1", [
    capped.device_code,
  ]);
  assert.equal((await poll(capped)).body.error, 'invalid_grant');
  assert.equal((await evidence(capped)).poll_count, 120);

  const racing = await issue();
  const pendingRace = await Promise.all([poll(racing), poll(racing)]);
  assert.deepEqual(pendingRace.map((x) => x.body.error).sort(), ['authorization_pending', 'slow_down']);
  await approve(racing);
  await advance(racing);
  const redemptions = await Promise.all([poll(racing), poll(racing)]);
  assert.equal(redemptions.filter((x) => x.status === 200).length, 1);
  assert.equal(redemptions.filter((x) => x.status === 400).length, 1);

  const live = await issue();
  await approve(live);
  const liveTokens = await poll(live);
  assert.equal(liveTokens.status, 200);
  assert.equal(await status(liveTokens.body.access_token), true);
  await pool.query("UPDATE actors SET status='disabled' WHERE id=$1", [actor.id]);
  assert.equal(await status(liveTokens.body.access_token), false);
  await pool.query("UPDATE actors SET status='active' WHERE id=$1", [actor.id]);
  const waitingForLogout = await issue();
  await approve(waitingForLogout);
  await pool.query('DELETE FROM sessions WHERE actor_id=$1', [actor.id]);
  assert.equal(await status(liveTokens.body.access_token), false);
  assert.equal(
    (
      await boundedFetch(discovery.userinfo_endpoint, {
        headers: { authorization: `Bearer ${liveTokens.body.access_token}` },
      })
    ).status,
    401,
  );
  assert.equal((await poll(waitingForLogout)).body.error, 'invalid_grant');
  const signedOutDevice = await boundedFetch(`${origin}/oidc/device`, {
    redirect: 'manual', headers: { cookie: cookie() },
  });
  assert.equal(signedOutDevice.status, 302);
  assert.equal(new URL(signedOutDevice.headers.get('location'), origin).searchParams.get('oidc_return'), '/oidc/device');
  const bindings = await pool.query('SELECT token_hash FROM axxium_oidc_grant_sessions WHERE actor_id=$1', [
    actor.id,
  ]);
  assert.ok(bindings.rows.length > 0);
  for (const row of bindings.rows) assert.match(row.token_hash, /^[0-9a-f]{64}$/);
  const records = await pool.query('SELECT payload::text FROM axxium_oidc_records');
  for (const row of records.rows) {
    assert.ok(!row.payload.includes(rootToken));
    for (const owner of clients) assert.ok(!row.payload.includes(owner.secret));
  }
  assert.ok(consentCount >= 5);
  console.log(
    'PASS mounted RFC8628: authenticated atomic polling/backoff, explicit root approval/denial, browser Origin/XSRF, expiry/retry caps, single redemption, actor/client binding and root logout',
  );
}
