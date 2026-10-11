#!/usr/bin/env node
// Prepare one host's runtime without printing or transporting private credentials.
import { mkdirSync, existsSync, writeFileSync, readFileSync, copyFileSync, chmodSync } from 'node:fs';
import { resolve } from 'node:path';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
const [directory, environment, publicUrl, image] = process.argv.slice(2);
if (!directory || !/^(stealth|yoga|testing|staging|production)$/.test(environment ?? '') || !image)
  throw new Error('Usage: prepare-instance.mjs DIRECTORY ENV PUBLIC_URL IMAGE');
const target = resolve(directory);
const url = new URL(publicUrl);
if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/')
  throw new Error('PUBLIC_URL must be an origin');
mkdirSync(target, { recursive: true, mode: 0o700 });
if (!existsSync(`${target}/identity-private.json`)) {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  writeFileSync(`${target}/identity-private.json`, JSON.stringify(privateKey.export({ format: 'jwk' })), { mode: 0o600 });
  writeFileSync(`${target}/identity-public.json`, JSON.stringify(publicKey.export({ format: 'jwk' })), { mode: 0o644 });
}
if (!existsSync(`${target}/trust.json`)) writeFileSync(`${target}/trust.json`, '{}\n', { mode: 0o600 });
if (!existsSync(`${target}/.env`)) {
  const values = { AXXIUM_IMAGE: image, AXXIUM_PUBLIC_BASE_URL: url.origin,
    AXXIUM_LISTEN_PORT: '18877', DB_PASSWORD: randomBytes(32).toString('hex'),
    JWT_SECRET: randomBytes(48).toString('base64url'), SESSION_COOKIE_SECURE: String(url.protocol === 'https:') };
  writeFileSync(`${target}/.env`, Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { mode: 0o600 });
} else {
  const text = readFileSync(`${target}/.env`, 'utf8');
  if (!text.includes(`AXXIUM_PUBLIC_BASE_URL=${url.origin}\n`)) throw new Error('Existing instance origin differs; migration must be explicit');
  writeFileSync(`${target}/.env`, text.replace(/^AXXIUM_IMAGE=.*$/m, `AXXIUM_IMAGE=${image}`));
}
copyFileSync(new URL('../compose.instance.yaml', import.meta.url), `${target}/compose.yaml`);
chmodSync(`${target}/.env`, 0o600);
console.log(`Prepared ${environment} Axxium runtime at ${target}; credentials remain local.`);
