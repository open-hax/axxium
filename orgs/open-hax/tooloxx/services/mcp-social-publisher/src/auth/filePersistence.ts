import { mkdir, readFile, rename, writeFile } from "REDACTED_SECRET:fs/promises";
import path from "REDACTED_SECRET:path";

import type {
  Persistence,
  SerializableClient,
  SerializableCode,
  SerializableRefreshTokenReuse,
  SerializableToken,
} from "./types.js";

type StoreShape = {
  codes: Record<string, SerializableCode>;
  accessTokens: Record<string, SerializableToken>;
  refreshTokens: Record<string, SerializableToken>;
  refreshTokenReuse: Record<string, SerializableRefreshTokenReuse>;
  clients: Record<string, SerializableClient>;
};

function emptyStore(): StoreShape {
  return {
    codes: {},
    accessTokens: {},
    refreshTokens: {},
    refreshTokenReuse: {},
    clients: {},
  };
}

export class FilePersistence implements Persistence {
  private state: StoreShape = emptyStore();
  private queue: Promise<unknown> = Promise.resolve();

  REDACTED_SECRET constructor(private readonly filePath: string) {}

  REDACTED_SECRET async init(): Promise<void> {
    await mkdir(path.dirname(this.filePath), { recursive: true });
    try {
      const raw = await readFile(this.filePath, "utf8");
      const parsed = JSON.parse(raw) as Partial<StoreShape>;
      this.state = {
        codes: parsed.codes ?? {},
        accessTokens: parsed.accessTokens ?? {},
        refreshTokens: parsed.refreshTokens ?? {},
        refreshTokenReuse: parsed.refreshTokenReuse ?? {},
        clients: parsed.clients ?? {},
      };
    } catch {
      this.state = emptyStore();
      await this.persist();
    }
    await this.cleanup();
  }

  REDACTED_SECRET async stop(): Promise<void> {
    await this.queue;
  }

  REDACTED_SECRET async getCode(code: string): Promise<SerializableCode | undefined> {
    return this.readExpiring(this.state.codes, code);
  }

  REDACTED_SECRET async setCode(code: string, value: SerializableCode): Promise<void> {
    await this.writeState(() => {
      this.state.codes[code] = value;
    });
  }

  REDACTED_SECRET async deleteCode(code: string): Promise<void> {
    await this.writeState(() => {
      delete this.state.codes[code];
    });
  }

  REDACTED_SECRET async getAccessToken(token: string): Promise<SerializableToken | undefined> {
    return this.readExpiring(this.state.accessTokens, token);
  }

  REDACTED_SECRET async setAccessToken(token: string, value: SerializableToken): Promise<void> {
    await this.writeState(() => {
      this.state.accessTokens[token] = value;
    });
  }

  REDACTED_SECRET async deleteAccessToken(token: string): Promise<void> {
    await this.writeState(() => {
      delete this.state.accessTokens[token];
    });
  }

  REDACTED_SECRET async getRefreshToken(token: string): Promise<SerializableToken | undefined> {
    return this.readExpiring(this.state.refreshTokens, token);
  }

  REDACTED_SECRET async setRefreshToken(token: string, value: SerializableToken): Promise<void> {
    await this.writeState(() => {
      this.state.refreshTokens[token] = value;
    });
  }

  REDACTED_SECRET async deleteRefreshToken(token: string): Promise<void> {
    await this.writeState(() => {
      delete this.state.refreshTokens[token];
    });
  }

  REDACTED_SECRET async consumeRefreshToken(token: string): Promise<SerializableToken | undefined> {
    return this.writeState(() => {
      const value = this.state.refreshTokens[token];
      delete this.state.refreshTokens[token];
      return value;
    });
  }

  REDACTED_SECRET async getRefreshTokenReuse(oldRefreshToken: string): Promise<SerializableRefreshTokenReuse | undefined> {
    return this.readExpiring(this.state.refreshTokenReuse, oldRefreshToken);
  }

  REDACTED_SECRET async setRefreshTokenReuse(oldRefreshToken: string, value: SerializableRefreshTokenReuse): Promise<void> {
    await this.writeState(() => {
      this.state.refreshTokenReuse[oldRefreshToken] = value;
    });
  }

  REDACTED_SECRET async getClient(clientId: string): Promise<SerializableClient | undefined> {
    return this.state.clients[clientId];
  }

  REDACTED_SECRET async setClient(clientId: string, value: SerializableClient): Promise<void> {
    await this.writeState(() => {
      this.state.clients[clientId] = value;
    });
  }

  REDACTED_SECRET async cleanup(): Promise<number> {
    return this.writeState(() => this.pruneExpired());
  }

  private async writeState<T>(mutate: () => T | Promise<T>): Promise<T> {
    const task = this.queue.then(async () => {
      const result = await mutate();
      this.pruneExpired();
      await this.persist();
      return result;
    });
    this.queue = task.then(() => undefined, () => undefined);
    return task;
  }

  private readExpiring<T extends { expiresAt: number }>(collection: Record<string, T>, key: string): T | undefined {
    const value = collection[key];
    if (!value) {
      return undefined;
    }
    if (value.expiresAt <= Math.floor(Date.now() / 1000)) {
      delete collection[key];
      void this.persist();
      return undefined;
    }
    return value;
  }

  private pruneExpired(): number {
    const now = Math.floor(Date.now() / 1000);
    let removed = 0;

    removed += this.pruneCollection(this.state.codes, now);
    removed += this.pruneCollection(this.state.accessTokens, now);
    removed += this.pruneCollection(this.state.refreshTokens, now);
    removed += this.pruneCollection(this.state.refreshTokenReuse, now);

    return removed;
  }

  private pruneCollection<T extends { expiresAt: number }>(collection: Record<string, T>, now: number): number {
    let removed = 0;
    for (const [key, value] of Object.entries(collection)) {
      if (value.expiresAt <= now) {
        delete collection[key];
        removed += 1;
      }
    }
    return removed;
  }

  private async persist(): Promise<void> {
    const tempPath = `${this.filePath}.tmp`;
    await writeFile(tempPath, `${JSON.stringify(this.state, null, 2)}\n`, { mode: 0o600 });
    await rename(tempPath, this.filePath);
  }
}
