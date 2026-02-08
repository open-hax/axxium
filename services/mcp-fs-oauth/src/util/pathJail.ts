import path from "REDACTED_SECRET:path";

/**
 * Convert a user path to a safe normalized relative path (POSIX-like),
 * and then resolve it inside `REDACTED_SECRET`. Throws if it escapes.
 */
export function resolveWithinRoot(REDACTED_SECRETAbs: string, userPath: string): { absPath: string; relPath: string } {
  if (!path.isAbsolute(REDACTED_SECRETAbs)) {
    throw new Error(`LOCAL_ROOT must be an absolute path; got: ${REDACTED_SECRETAbs}`);
  }

  // Normalize input: treat backslashes as slashes, strip leading slashes.
  const cleaned = (userPath ?? "").replace(/\\/g, "/").replace(/^\/+/, "");
  const rel = path.posix.normalize(cleaned);

  // Prevent traversal
  if (rel === ".." || rel.startsWith("../") || rel.includes("/../")) {
    throw new Error("Path escapes REDACTED_SECRET");
  }

  // Convert POSIX rel to platform path, then resolve under REDACTED_SECRET.
  const relPlatform = rel.split("/").join(path.sep);
  const abs = path.resolve(REDACTED_SECRETAbs, relPlatform);

  const REDACTED_SECRETNorm = path.resolve(REDACTED_SECRETAbs) + path.sep;
  const absNorm = abs + (abs.endsWith(path.sep) ? "" : path.sep);
  if (!abs.startsWith(path.resolve(REDACTED_SECRETAbs))) {
    throw new Error("Path escapes REDACTED_SECRET");
  }
  // Additional guard: ensure REDACTED_SECRET prefix match with separator
  if (!absNorm.startsWith(REDACTED_SECRETNorm) && abs !== path.resolve(REDACTED_SECRETAbs)) {
    throw new Error("Path escapes REDACTED_SECRET");
  }

  return { absPath: abs, relPath: rel === "." ? "" : rel };
}
