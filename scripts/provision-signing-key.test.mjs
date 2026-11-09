import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { calculateJwkThumbprint } from "jose";

const script = "scripts/provision-signing-key.mjs";

function run(args) {
  return spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });
}

test("provision writes a mode 0600 key and prints only the public JWK", async () => {
  const dir = mkdtempSync(join(tmpdir(), "axxium-sign-"));
  const path = join(dir, "signing-key.pem");
  try {
    const created = run([path]);
    assert.equal(created.status, 0);
    assert.equal(created.stderr.length, 0);
    const printed = JSON.parse(created.stdout);
    assert.equal(printed.jwk.kty, "OKP");
    assert.equal(printed.jwk.crv, "Ed25519");
    assert.equal(printed.jwk.alg, "EdDSA");
    assert.equal(printed.jwk.use, "sig");
    assert.equal(printed.jwk.kid, printed.kid);
    assert.equal(Object.hasOwn(printed.jwk, "d"), false);
    assert.equal(Object.hasOwn(printed, "pem"), false);
    assert.equal(printed.jwk.x.length, 43);
    assert.equal(
      printed.kid,
      await calculateJwkThumbprint(
        { kty: printed.jwk.kty, crv: printed.jwk.crv, x: printed.jwk.x },
        "sha256"));
    const pem = readFileSync(path, "utf8");
    assert.equal(pem.startsWith("-----BEGIN PRIVATE KEY-----"), true);
    assert.equal(statSync(path).mode & 0o777, 0o600);

    const again = run([path]);
    assert.equal(again.status, 1);
    assert.equal(again.stdout.length, 0);
    assert.equal(again.stderr, "refusing to overwrite an existing signing key\n");
    assert.equal(readFileSync(path, "utf8").startsWith("-----BEGIN PRIVATE KEY-----"), true);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("provision without a path does not write a key", () => {
  const missing = run([]);
  assert.equal(missing.status, 2);
  assert.equal(missing.stdout.length, 0);
  assert.equal(missing.stderr.includes("usage:"), true);
});
