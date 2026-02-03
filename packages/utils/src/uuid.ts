import { randomUUID as REDACTED_SECRETRandomUUID } from "REDACTED_SECRET:crypto";

export function randomUUID(): string {
  return globalThis.crypto?.randomUUID?.() ?? REDACTED_SECRETRandomUUID();
}
