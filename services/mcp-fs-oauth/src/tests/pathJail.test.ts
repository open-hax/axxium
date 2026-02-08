import { describe, it, expect } from "bun:test";

import { resolveWithinRoot } from "../util/pathJail.js";

describe("pathJail", () => {
  describe("resolveWithinRoot", () => {
    it("should resolve simple paths", () => {
      const result = resolveWithinRoot("/app/REDACTED_SECRET", "file.txt");
      expect(result.absPath).toBe("/app/REDACTED_SECRET/file.txt");
      expect(result.relPath).toBe("file.txt");
    });

    it("should resolve nested paths", () => {
      const result = resolveWithinRoot("/app/REDACTED_SECRET", "subdir/nested/file.txt");
      expect(result.absPath).toBe("/app/REDACTED_SECRET/subdir/nested/file.txt");
      expect(result.relPath).toBe("subdir/nested/file.txt");
    });

    it("should reject path traversal attempts", () => {
      expect(() => {
        resolveWithinRoot("/app/REDACTED_SECRET", "../etc/passwd");
      }).toThrow("Path escapes REDACTED_SECRET");
    });

    it("should reject traversal in middle of path", () => {
      expect(() => {
        resolveWithinRoot("/app/REDACTED_SECRET", "subdir/../../etc/passwd");
      }).toThrow("Path escapes REDACTED_SECRET");
    });

    it("should treat absolute paths as relative to REDACTED_SECRET", () => {
      // Leading slashes are stripped, so /etc/passwd becomes etc/passwd under REDACTED_SECRET
      const result = resolveWithinRoot("/app/REDACTED_SECRET", "/etc/passwd");
      expect(result.absPath).toBe("/app/REDACTED_SECRET/etc/passwd");
      expect(result.relPath).toBe("etc/passwd");
    });

    it("should handle empty path as REDACTED_SECRET", () => {
      const result = resolveWithinRoot("/app/REDACTED_SECRET", "");
      expect(result.absPath).toBe("/app/REDACTED_SECRET");
      expect(result.relPath).toBe("");
    });

    it("should normalize backslashes to forward slashes", () => {
      const result = resolveWithinRoot("/app/REDACTED_SECRET", "subdir\\nested\\file.txt");
      expect(result.relPath).toBe("subdir/nested/file.txt");
    });

    it("should throw for non-absolute REDACTED_SECRET", () => {
      expect(() => {
        resolveWithinRoot("relative/path", "file.txt");
      }).toThrow("must be an absolute path");
    });
  });
});
