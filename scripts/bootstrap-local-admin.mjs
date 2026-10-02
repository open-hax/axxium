// Run once with a private admin env file and database configuration.
import { randomUUID } from 'node:crypto';
import bcrypt from 'bcryptjs';
import pg from 'pg';

const { AXXIUM_ADMIN_EMAIL, AXXIUM_ADMIN_PASSWORD, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD } = process.env;
const email = AXXIUM_ADMIN_EMAIL?.trim().toLowerCase();
if (!email || !AXXIUM_ADMIN_PASSWORD || AXXIUM_ADMIN_PASSWORD.length < 16) {
  throw new Error('A private administrator email and password of at least 16 characters are required');
}

const client = new pg.Client({ host: DB_HOST, port: Number(DB_PORT || 5432),
  database: DB_NAME, user: DB_USER, password: DB_PASSWORD });
await client.connect();
try {
  await client.query('BEGIN');
  const existing = await client.query('SELECT id FROM actors WHERE email = $1 FOR UPDATE', [email]);
  if (existing.rowCount) throw new Error('Administrator email is already bound; refusing to elevate an existing account');
  const entityId = `entity_${randomUUID()}`;
  const actorId = `actor_${randomUUID()}`;
  await client.query('INSERT INTO entities (id, kind, email, display_name) VALUES ($1, $2, $3, $4)',
    [entityId, 'human', email, 'System administrator']);
  await client.query(`INSERT INTO actors
    (id, entity_id, email, display_name, password_hash, capabilities, roles, status)
    VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8)`,
    [actorId, entityId, email, 'System administrator', await bcrypt.hash(AXXIUM_ADMIN_PASSWORD, 12),
      JSON.stringify(['axxium/login', 'axxium/read']), JSON.stringify(['axxium/system-admin']), 'active']);
  await client.query('COMMIT');
  process.stdout.write(`Created system administrator ${actorId}\n`);
} catch (error) {
  await client.query('ROLLBACK');
  throw error;
} finally {
  await client.end();
}
