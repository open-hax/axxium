/**
 * Write a new Ed25519 PKCS#8 signing key. Refuses to overwrite.
 * Stdout is the kid and the public JWK. The PEM is only written to the file.
 */
import { generateKeyPair, exportPKCS8, exportJWK, calculateJwkThumbprint } from "jose";
import { writeFileSync } from "node:fs";

const path = process.argv[2];

if (!path) {
  console.error("usage: node scripts/provision-signing-key.mjs <path>");
  process.exit(2);
}

const { privateKey, publicKey } = await generateKeyPair("EdDSA", { crv: "Ed25519" });
const pem = await exportPKCS8(privateKey);
const exported = await exportJWK(publicKey);
const minimal = { kty: exported.kty, crv: exported.crv, x: exported.x };
const kid = await calculateJwkThumbprint(minimal, "sha256");
const jwk = { kty: "OKP", crv: "Ed25519", x: exported.x, kid, use: "sig", alg: "EdDSA" };

try {
  writeFileSync(path, pem, { mode: 0o600, flag: "wx" });
} catch (err) {
  if (err && err.code === "EEXIST") {
    console.error("refusing to overwrite an existing signing key");
  } else {
    console.error("cannot write signing key");
  }
  process.exit(1);
}

process.stdout.write(`${JSON.stringify({ kid, jwk })}\n`);
