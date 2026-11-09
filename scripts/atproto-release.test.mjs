import assert from 'node:assert/strict';
import test from 'node:test';
import { authorize, startLimiterKey } from '../target/atproto-oauth/atproto.js';

test('the AT Protocol ESM release canonicalizes client addresses for rate limiting', () => {
  assert.equal(startLimiterKey('203.0.113.7'), '203.0.113.7');
  assert.equal(startLimiterKey('::ffff:203.0.113.7'), '203.0.113.7');
  assert.equal(startLimiterKey('2001:db8:abcd:12::1'), '2001:db8:abcd:12::/64');
  assert.equal(startLimiterKey('2001:db8:abcd:12::ff'), '2001:db8:abcd:12::/64');
});

test('the AT Protocol ESM release guards discovery and preserves DPoP PAR', async t => {
  const origin = 'https://login.acme.net';
  const did = 'did:web:login.acme.net';
  const denials = [];
  t.mock.method(globalThis, 'fetch', async (request, options) => {
    assert.ok(options?.dispatcher);
    const url = new URL(request.url);
    if (url.origin !== origin) {
      // Exercise the installed guard without a valid dispatch. Even if the
      // guard is absent, the invalid method prevents opening a connection.
      try {
        options.dispatcher.dispatch({ origin: url.origin, path: '/', method: null }, {});
      } catch (error) {
        denials.push(error.message);
        throw error;
      }
      assert.fail('The dispatcher did not refuse the private address');
    }
    switch (url.pathname) {
      case '/.well-known/did.json':
        return Response.json({ id: did, service: [{
          id: '#atproto_pds', type: 'AtprotoPersonalDataServer', serviceEndpoint: origin,
        }] });
      case '/.well-known/oauth-protected-resource':
        return Response.json({ resource: origin, authorization_servers: [origin] });
      case '/.well-known/oauth-authorization-server':
        return Response.json({
          issuer: origin,
          authorization_endpoint: origin + '/authorize',
          token_endpoint: origin + '/token',
          pushed_authorization_request_endpoint: origin + '/par',
          require_pushed_authorization_requests: true,
          token_endpoint_auth_methods_supported: ['none'],
          client_id_metadata_document_supported: true,
          code_challenge_methods_supported: ['S256'],
          dpop_signing_alg_values_supported: ['ES256'],
        });
      case '/par': {
        assert.equal(request.method, 'POST');
        assert.equal(request.redirect, 'error');
        assert.ok(request.headers.has('DPoP'));
        const fields = new URLSearchParams(await request.text());
        assert.equal(fields.get('code_challenge_method'), 'S256');
        return Response.json({ request_uri: 'urn:ietf:params:oauth:request_uri:test', expires_in: 60 }, { status: 201 });
      }
      default:
        assert.fail(`Unexpected OAuth fixture path ${url.pathname}`);
    }
  });

  for (const identity of ['did:web:127.0.0.1', 'did:web:192.168.0.1', 'did:web:169.254.169.254']) {
    await assert.rejects(() => authorize(identity, 'fixture-browser-state'));
  }
  assert.equal(denials.length, 3);
  for (const denial of denials) assert.match(denial, /Blocked by url policy .* non-unicast address/);

  const redirect = new URL(await authorize(did, 'fixture-browser-state'));
  assert.equal(redirect.origin + redirect.pathname, origin + '/authorize');
  assert.equal(redirect.searchParams.get('request_uri'), 'urn:ietf:params:oauth:request_uri:test');
});
