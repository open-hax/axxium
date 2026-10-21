// Local end-to-end proof; load private local.env and admin.env with Node --env-file.
import pg from 'pg';
import crypto from 'node:crypto';

const origin = process.env.AXXIUM_PUBLIC_BASE_URL;
if (!origin?.startsWith('http://127.0.0.1:')) throw new Error('A loopback Axxium origin is required');
if (!process.env.AXXIUM_ADMIN_EMAIL || !process.env.AXXIUM_ADMIN_PASSWORD) {
  throw new Error('Load the private administrator environment before verification');
}

const db = new pg.Client({ host: process.env.DB_HOST, port: Number(process.env.DB_PORT),
  database: process.env.DB_NAME, user: process.env.DB_USER, password: process.env.DB_PASSWORD });
let agentId;
let entityId;
let humanId;
let humanEntityId;

async function expect(label, response, status) {
  const body = await response.json();
  if (response.status !== status) throw new Error(`${label}: expected ${status}, got ${response.status} (${body.error || 'unknown'})`);
  process.stdout.write(`PASS ${label}\n`);
  return body;
}

try {
  const config = await expect('public auth configuration responds',
    await fetch(`${origin}/api/auth/config`), 200);
  if (config.googleEnabled !== true) throw new Error('Google client is not enabled');
  if (config.atprotoEnabled !== true) throw new Error('AT Protocol sign-in is not enabled');
  const googleStart = await fetch(`${origin}/api/auth/google/start`, { redirect: 'manual' });
  const authorize = new URL(googleStart.headers.get('location'));
  if (googleStart.status !== 302 || authorize.origin !== 'https://accounts.google.com' ||
      authorize.searchParams.get('redirect_uri') !== `${origin}/api/auth/google/callback` ||
      authorize.searchParams.get('code_challenge_method') !== 'S256' ||
      !googleStart.headers.get('set-cookie')?.includes('HttpOnly')) {
    throw new Error('Google authorization redirect lacks expected callback, PKCE or browser state');
  }
  process.stdout.write('PASS Google authorization uses the registered callback, PKCE and browser state\n');
  await expect('Google callback rejects state without its browser cookie',
    await fetch(`${origin}/api/auth/google/callback?state=${encodeURIComponent(authorize.searchParams.get('state'))}&code=invalid`), 400);
  const metadataResponse = await fetch(`${origin}/api/auth/atproto/client-metadata.json`);
  const metadata = await expect('AT Protocol OAuth client metadata responds', metadataResponse, 200);
  if (metadataResponse.headers.get('content-type')?.split(';')[0] !== 'application/json' ||
      !metadata.client_id.startsWith('http://localhost/?') ||
      metadata.redirect_uris[0] !== `${origin}/api/auth/atproto/callback` ||
      metadata.scope !== 'atproto' || metadata.dpop_bound_access_tokens !== true) {
    throw new Error('Local AT Protocol metadata is not the loopback OAuth client');
  }
  const atprotoStart = await fetch(`${origin}/api/auth/atproto/start?identity=atproto.com`,
    { redirect: 'manual' });
  if (atprotoStart.status !== 302 ||
      new URL(atprotoStart.headers.get('location')).protocol !== 'https:' ||
      !atprotoStart.headers.get('set-cookie')?.includes('HttpOnly')) {
    throw new Error('AT Protocol authorization lacks verified discovery or browser state');
  }
  process.stdout.write('PASS AT Protocol authorization reaches HTTPS server with browser state\n');
  await expect('AT Protocol callback rejects a missing browser state',
    await fetch(`${origin}/api/auth/atproto/callback?state=invalid&code=invalid`), 400);
  await expect('anonymous AT identity linking refused',
    await fetch(`${origin}/api/auth/atproto/start?link=1&identity=atproto.com`), 403);
  await expect('anonymous actor listing refused', await fetch(`${origin}/api/actors`), 401);
  const loginResponse = await fetch(`${origin}/api/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: process.env.AXXIUM_ADMIN_EMAIL,
      password: process.env.AXXIUM_ADMIN_PASSWORD }),
  });
  const login = await expect('human administrator signs in', loginResponse, 200);
  if (!login.actor.roles.includes('axxium/system-admin')) throw new Error('Administrator role absent');
  const cookie = loginResponse.headers.get('set-cookie').split(';')[0];
  const linkStart = await fetch(`${origin}/api/auth/atproto/start?link=1&identity=atproto.com`,
    { redirect: 'manual', headers: { cookie } });
  if (linkStart.status !== 302 ||
      !linkStart.headers.get('set-cookie')?.includes('axxium_atproto_state=link%7C')) {
    throw new Error('Human actor cannot start a DID account link');
  }
  process.stdout.write('PASS human actor starts an explicit AT identity link\n');
  const signupResponse = await fetch(`${origin}/api/auth/signup`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: `verify-${crypto.randomUUID()}@localhost.test`,
      password: crypto.randomBytes(24).toString('base64url') }),
  });
  const human = await expect('new user becomes a human actor', signupResponse, 200);
  humanId = human.actor.id;
  humanEntityId = human.actor.entity_id;
  const humanCookie = signupResponse.headers.get('set-cookie').split(';')[0];
  await expect('ordinary human cannot list actors',
    await fetch(`${origin}/api/actors`, { headers: { cookie: humanCookie } }), 403);
  const actorList = await expect('administrator lists human actors',
    await fetch(`${origin}/api/actors`, { headers: { cookie } }), 200);
  if (!Array.isArray(actorList.actors) ||
      !actorList.actors.some(actor => actor.id === humanId && actor.kind === 'human')) {
    throw new Error('Actor listing omitted the human kind');
  }
  const handle = await expect('administrator resolves a bidirectionally verified AT handle',
    await fetch(`${origin}/api/atproto/resolve?identity=atproto.com`, { headers: { cookie } }), 200);
  if (!handle.identity.did.startsWith('did:plc:') || !handle.identity.pds.startsWith('https://')) {
    throw new Error('AT identity has no DID or secure PDS endpoint');
  }
  const did = await expect('DID resolves to the same handle and PDS',
    await fetch(`${origin}/api/atproto/resolve?identity=${encodeURIComponent(handle.identity.did)}`,
      { headers: { cookie } }), 200);
  if (did.identity.handle !== handle.identity.handle || did.identity.pds !== handle.identity.pds) {
    throw new Error('DID and handle resolution disagree');
  }
  const created = await expect('administrator creates agent actor', await fetch(`${origin}/api/actors/agents`, {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ 'display-name': 'Local identity verification agent' }),
  }), 201);
  agentId = created.actor.id;
  entityId = created.actor.entity_id;
  const issuanceResponse = await fetch(`${origin}/api/actors/${agentId}/credentials`, {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ label: 'verification', 'expires-in-hours': 1 }),
  });
  const issued = await expect('administrator issues expiring credential',
    issuanceResponse, 201);
  if (issuanceResponse.headers.get('cache-control') !== 'no-store') {
    throw new Error('One-time credential response is cacheable');
  }
  const bearer = { authorization: `Bearer ${issued.token}` };
  const listedCredentials = await expect('administrator lists agent credential metadata',
    await fetch(`${origin}/api/actors/${agentId}/credentials`, { headers: { cookie } }), 200);
  if (!listedCredentials.credentials.some(credential => credential.id === issued.credential.id)) {
    throw new Error('Issued credential missing from account management list');
  }
  await expect('unknown credential cannot be revoked',
    await fetch(`${origin}/api/actors/${agentId}/credentials/${crypto.randomUUID()}`,
      { method: 'DELETE', headers: { cookie } }), 404);
  await expect('agent bearer resolves its own identity',
    await fetch(`${origin}/api/auth/me`, { headers: bearer }), 200);
  await expect('agent bearer cannot create another agent',
    await fetch(`${origin}/api/actors/agents`, {
      method: 'POST', headers: { ...bearer, 'content-type': 'application/json' },
      body: JSON.stringify({ 'display-name': 'Forbidden agent' }),
    }), 403);
  await expect('agent bearer cannot use administrator AT lookup',
    await fetch(`${origin}/api/atproto/resolve?identity=atproto.com`, { headers: bearer }), 403);
  await expect('administrator revokes credential',
    await fetch(`${origin}/api/actors/${agentId}/credentials/${issued.credential.id}`, {
      method: 'DELETE', headers: { cookie },
    }), 200);
  await expect('revoked bearer loses access',
    await fetch(`${origin}/api/auth/me`, { headers: bearer }), 401);
} finally {
  if (agentId || humanId) {
    await db.connect();
    try {
      if (agentId) {
        await db.query('DELETE FROM agent_credentials WHERE actor_id = $1', [agentId]);
        await db.query('DELETE FROM actors WHERE id = $1', [agentId]);
        await db.query('DELETE FROM entities WHERE id = $1', [entityId]);
        process.stdout.write('Cleaned verification actor and credentials\n');
      }
      if (humanId) {
        await db.query('DELETE FROM sessions WHERE actor_id = $1', [humanId]);
        await db.query('DELETE FROM actors WHERE id = $1', [humanId]);
        await db.query('DELETE FROM entities WHERE id = $1', [humanEntityId]);
        process.stdout.write('Cleaned verification human and session\n');
      }
    } finally {
      await db.end();
    }
  }
}
