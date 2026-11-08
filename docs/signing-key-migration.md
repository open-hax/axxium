# Signing-key migration

Every JWT this process signed with the shared HS256 `JWT_SECRET` is now invalid. That includes actor session tokens and Google OAuth state cookies that are still in flight. People need to sign in again. Agent bearer credentials that start with `axx_` are random secrets stored as SHA-256 hashes, not JWTs, so those credentials keep working.

The replacement is one Ed25519 key per instance. Tokens are EdDSA. The header `kid` is the key id. Verifiers read the public key from `GET /.well-known/jwks.json`. `GET /.well-known/did.json` publishes the same key as a did:web document for `AXXIUM_PUBLIC_BASE_URL` (path, query, and fragment are not part of the id).

## Provision a key

Run this once per instance. The command writes a PKCS#8 PEM with mode `0600` and refuses to overwrite an existing file. Standard output is the `kid` and the public JWK. The private key is not printed.

```sh
node scripts/provision-signing-key.mjs ./signing-key.pem
```

Then set:

| Variable | Meaning |
|----------|---------|
| `AXXIUM_SIGNING_KEY_FILE` | Path to that PEM. Required. There is no default key. |
| `AXXIUM_SIGNING_KID` | Optional. When unset, `kid` is the RFC 7638 SHA-256 thumbprint of the public JWK `{kty,crv,x}`. |
| `AXXIUM_ISS` | Instance issuer. Falls back to `JWT_ISSUER`, then `axxium`. |
| `AXXIUM_SERVICE` | Audience service. Default `promethean`. |
| `AXXIUM_ENV` | Audience environment. Falls back to `NODE_ENV`, then `development`. |
| `AXXIUM_PUBLIC_BASE_URL` | Origin used for callbacks and for the did:web id. |

Remove `JWT_SECRET`. The process does not read it. Leave `JWT_AUDIENCE` unset; a value other than `AXXIUM_SERVICE+AXXIUM_ENV` is refused at startup.

`docker-compose.yml` mounts `${AXXIUM_SIGNING_KEY_FILE_HOST:-./signing-key.pem}` read-only at `/run/axxium/signing-key.pem`. Provision the host file before `docker compose up`.

Restart the process. Startup logs the `kid` only. Confirm both well-known URLs return the public key and that the documents contain no private JWK member (`d`, `p`, `q`, `dp`, `dq`, `qi`, `k`, `oth`).

Do not commit the PEM. `signing-key.pem` is gitignored. Do not log the file, and do not pass its contents on a command line.

## What breaks

- Session JWTs signed with HS256 fail verification. Users sign in again.
- Google sign-in state cookies signed with HS256 fail. An in-flight Google redirect must be started over.
- Any verifier that still requires `alg=HS256`, the old shared secret, or `aud=promethean` (without `+env`) will reject tokens from this process. `aud` remains `service+env`, for example `promethean+production`.
