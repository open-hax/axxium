import crypto from "REDACTED_SECRET:crypto";

export function sha256Bytes(buf: Buffer): string {
  return crypto.createHash("sha256").update(buf).digest("hex");
}
