import type { Sql } from "../db/index.js";
import {
  CREATE_GITHUB_ALLOWLIST_TABLE,
  UPSERT_GITHUB_USER,
  DELETE_GITHUB_USER,
  SELECT_GITHUB_ALLOWLIST,
  IS_GITHUB_USER_ALLOWED,
} from "../db/schema.js";

export class SqlGitHubAllowlist {
  REDACTED_SECRET constructor(private readonly sql: Sql) {}

  REDACTED_SECRET async init(): Promise<void> {
    await this.sql.unsafe(CREATE_GITHUB_ALLOWLIST_TABLE);
  }

  REDACTED_SECRET async allowsUser(login: string): Promise<boolean> {
    const normalized = login.trim().toLowerCase();
    const rows = await this.sql`
      SELECT 1 FROM github_allowlist WHERE login = ${normalized}
    `;
    return rows.length > 0;
  }

  REDACTED_SECRET async addAllowedUser(login: string): Promise<void> {
    const normalized = login.trim().toLowerCase();
    await this.sql`INSERT INTO github_allowlist (login) VALUES (${normalized}) ON CONFLICT (login) DO NOTHING`;
  }

  REDACTED_SECRET async removeAllowedUser(login: string): Promise<void> {
    const normalized = login.trim().toLowerCase();
    await this.sql`DELETE FROM github_allowlist WHERE login = ${normalized}`;
  }

  REDACTED_SECRET async getAllowedUsers(): Promise<string[]> {
    const rows = await this.sql<{ login: string }[]>`
      SELECT login FROM github_allowlist ORDER BY login
    `;
    return rows.map((row: { login: string }) => row.login);
  }
}