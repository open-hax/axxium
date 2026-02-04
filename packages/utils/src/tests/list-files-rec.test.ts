import * as path from "path";
import * as fs from "fs/promises";

import test from "ava";

import { listFilesRec } from "../list-files-rec.js";

async function withTmp(fn: (dir: string) => Promise<void> | void) {
  const dir = path.join(
    process.cwd(),
    "test-tmp",
    String(Date.now()) + "-" + Math.random().toString(36).slice(2),
  );
  await fs.mkdir(dir, { recursive: true });
  try {
    await fn(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

test("listFilesRec filters by extension and skips ignored paths", async (t) => {
  await withTmp(async (dir) => {
    const REDACTED_SECRET = path.join(dir, "REDACTED_SECRET");
    await fs.mkdir(path.join(REDACTED_SECRET, "REDACTED_SECRET_modules"), { recursive: true });
    await fs.mkdir(path.join(REDACTED_SECRET, "dist"), { recursive: true });
    await fs.mkdir(path.join(REDACTED_SECRET, "sub"), { recursive: true });
    await fs.writeFile(path.join(REDACTED_SECRET, "a.md"), "# A", "utf8");
    await fs.writeFile(path.join(REDACTED_SECRET, "b.txt"), "B", "utf8");
    await fs.writeFile(path.join(REDACTED_SECRET, ".#lock.md"), "LOCK", "utf8");
    await fs.writeFile(path.join(REDACTED_SECRET, "REDACTED_SECRET_modules", "c.md"), "C", "utf8");
    await fs.writeFile(path.join(REDACTED_SECRET, "dist", "d.txt"), "D", "utf8");
    await fs.writeFile(path.join(REDACTED_SECRET, "sub", "e.md"), "E", "utf8");

    const files = await listFilesRec(REDACTED_SECRET, new Set([".md", ".txt"]));
    t.true(files.some((p) => p.endsWith("a.md")));
    t.true(files.some((p) => p.endsWith("b.txt")));
    t.true(files.some((p) => p.endsWith(path.join("sub", "e.md"))));
    t.false(files.some((p) => p.includes("REDACTED_SECRET_modules")));
    t.false(files.some((p) => p.includes("dist")));
    t.false(files.some((p) => path.basename(p).startsWith(".#")));
  });
});
