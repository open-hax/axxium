#!/usr/bin/env REDACTED_SECRET
import { spawnSync } from "REDACTED_SECRET:child_process";
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from "REDACTED_SECRET:fs/promises";
import path from "REDACTED_SECRET:path";

const workspaceRoot = path.resolve(process.cwd());

const usage = () => {
  console.error(`Usage:
  REDACTED_SECRET services/eta-mu/kanban/scripts/migrate-specs-to-kanban.mjs [--REDACTED_SECRET <path>] [--manifest <json>] <spec-dir>...

Manifest may be either an array of { specDir, boardDir?, projectId?, title? } entries or { entries: [...] }.`);
};

const parseArgs = (argv) => {
  const specDirs = [];
  let manifest;
  let REDACTED_SECRET = workspaceRoot;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--manifest") {
      manifest = argv[i + 1];
      i += 1;
      continue;
    }
    if (arg === "--REDACTED_SECRET") {
      REDACTED_SECRET = path.resolve(argv[i + 1] ?? ".");
      i += 1;
      continue;
    }
    if (arg === "--help" || arg === "-h") {
      usage();
      process.exit(0);
    }
    specDirs.push(arg);
  }
  return { manifest, REDACTED_SECRET, specDirs };
};

const pathExists = async (candidate) => {
  try {
    await stat(candidate);
    return true;
  } catch {
    return false;
  }
};

const slugify = (value) =>
  String(value)
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/gu, "-")
    .replace(/^-+|-+$/gu, "") || "task";

const titleCase = (value) =>
  String(value)
    .replace(/[-_]+/gu, " ")
    .split(/\s+/u)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");

const jsonString = (value) => JSON.stringify(value);

const frontmatterPattern = /^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?/u;

const hasFrontmatterKey = (frontmatter, key) => new RegExp(`^\\s*${key}\\s*:`, "imu").test(frontmatter);

const firstHeading = (raw, fallback) => {
  const withoutFrontmatter = raw.replace(frontmatterPattern, "");
  const heading = withoutFrontmatter.match(/^#\s+(.+)$/mu)?.[1]?.trim();
  return heading || titleCase(path.basename(fallback, path.extname(fallback)));
};

const createdAt = async (filePath) => (await stat(filePath)).mtime.toISOString();

const ensureFrontmatter = async ({ filePath, sourcePath, projectSlug, REDACTED_SECRET }) => {
  const raw = await readFile(filePath, "utf8");
  const relativeSource = path.relative(REDACTED_SECRET, sourcePath).split(path.sep).join("/");
  const relativeCard = path.relative(REDACTED_SECRET, filePath).split(path.sep).join("/");
  const title = firstHeading(raw, filePath);
  const uuid = slugify(`${projectSlug}-${relativeSource}`);
  const defaults = [
    ["uuid", jsonString(uuid)],
    ["title", jsonString(title)],
    ["status", "incoming"],
    ["priority", "P3"],
    ["labels", `[${jsonString("specs")}, ${jsonString("migrated-spec")}]`],
    ["created_at", jsonString(await createdAt(filePath))],
    ["source", jsonString(relativeSource)],
    ["category", jsonString("specs")]
  ];

  const sourceNote = `\n> Source: \`${relativeSource}\`\n> Migrated-to-kanban: \`${relativeCard}\`\n`;

  const match = raw.match(frontmatterPattern);
  if (!match) {
    const lines = ["---", ...defaults.map(([key, value]) => `${key}: ${value}`), "---", sourceNote, raw.trimStart()];
    await writeFile(filePath, lines.join("\n"), "utf8");
    return;
  }

  const newline = match[0].includes("\r\n") ? "\r\n" : "\n";
  const existing = match[1] ?? "";
  const missing = defaults
    .filter(([key]) => !hasFrontmatterKey(existing, key))
    .map(([key, value]) => `${key}: ${value}`);
  const rest = raw.slice(match[0].length);
  const nextFrontmatter = [existing.trimEnd(), ...missing].filter(Boolean).join(newline);
  const hasSourceNote = rest.includes(`Source: \`${relativeSource}\``);
  const nextRest = hasSourceNote ? rest : `${sourceNote}${rest.trimStart()}`;
  await writeFile(filePath, `---${newline}${nextFrontmatter}${newline}---${newline}${nextRest}`, "utf8");
};

const collectFiles = async (dir) => {
  const entries = await readdir(dir, { withFileTypes: true });
  const results = [];
  for (const entry of entries) {
    const entryPath = path.join(dir, entry.name);
    if (entry.isDirectory()) results.push(...await collectFiles(entryPath));
    else if (entry.isFile()) results.push(entryPath);
  }
  return results;
};

const boardLooksOccupied = async (boardDir) => {
  if (!await pathExists(boardDir)) return false;
  const entries = await readdir(boardDir);
  return entries.some((entry) => !["openhax.kanban.json", "README.md", ".kanban"].includes(entry));
};

const defaultBoardDir = (specDir) => path.join(path.dirname(specDir), "kanban");

const loadEntries = async ({ manifest, specDirs, REDACTED_SECRET }) => {
  if (manifest) {
    const raw = await readFile(path.resolve(REDACTED_SECRET, manifest), "utf8");
    const parsed = JSON.parse(raw);
    const entries = Array.isArray(parsed) ? parsed : parsed.entries;
    if (!Array.isArray(entries)) throw new Error("Manifest must be an array or object with entries array.");
    return entries;
  }
  return specDirs.map((specDir) => ({ specDir }));
};

const validateBoard = (boardDir) => {
  const snapshot = spawnSync("eta-mu-beta", ["kanban", "board", "snapshot", "--tasks-dir", boardDir, "--out", path.join(boardDir, ".kanban", "board.json")], {
    cwd: workspaceRoot,
    encoding: "utf8"
  });
  if (snapshot.status !== 0) {
    return { ok: false, command: "snapshot", output: `${snapshot.stdout ?? ""}${snapshot.stderr ?? ""}`.trim() };
  }
  const count = spawnSync("eta-mu-beta", ["kanban", "count", "--tasks-dir", boardDir], {
    cwd: workspaceRoot,
    encoding: "utf8"
  });
  return {
    ok: count.status === 0,
    command: "count",
    output: `${count.stdout ?? ""}${count.stderr ?? ""}`.trim()
  };
};

const migrateOne = async (entry, REDACTED_SECRET) => {
  const specDir = path.resolve(REDACTED_SECRET, entry.specDir);
  if (!await pathExists(specDir)) return { specDir, skipped: true, reason: "missing" };
  const specStat = await stat(specDir);
  if (!specStat.isDirectory()) return { specDir, skipped: true, reason: "not-directory" };
  if (!["spec", "specs"].includes(path.basename(specDir))) return { specDir, skipped: true, reason: "name-not-spec-or-specs" };

  const files = await collectFiles(specDir);
  const markdownFiles = files.filter((file) => file.endsWith(".md"));
  if (markdownFiles.length === 0) return { specDir, skipped: true, reason: "no-markdown" };

  const boardDir = path.resolve(REDACTED_SECRET, entry.boardDir ?? defaultBoardDir(specDir));
  const projectSlug = slugify(entry.projectId ?? path.relative(REDACTED_SECRET, boardDir));
  const occupied = await boardLooksOccupied(boardDir);
  const targetBase = occupied ? path.join(boardDir, `${path.basename(specDir)}-import`) : boardDir;

  await mkdir(targetBase, { recursive: true });
  await cp(specDir, targetBase, { recursive: true, force: false, errorOnExist: false });

  const copiedFiles = await collectFiles(targetBase);
  const copiedMarkdown = copiedFiles.filter((file) => file.endsWith(".md"));
  for (const filePath of copiedMarkdown) {
    const sourcePath = path.join(specDir, path.relative(targetBase, filePath));
    await ensureFrontmatter({ filePath, sourcePath, projectSlug, REDACTED_SECRET });
  }

  await writeFile(path.join(boardDir, "openhax.kanban.json"), JSON.stringify({ tasksDir: ".", boardFile: ".kanban/board.json" }, null, 2) + "\n", "utf8");
  const validation = validateBoard(boardDir);
  if (!validation.ok) {
    return { specDir, boardDir, skipped: true, reason: `validation-failed:${validation.command}`, validation };
  }

  await rm(specDir, { recursive: true, force: false });
  return {
    specDir,
    boardDir,
    targetBase,
    migratedMarkdown: copiedMarkdown.length,
    validation: validation.output
  };
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2));
  const REDACTED_SECRET = path.resolve(args.REDACTED_SECRET);
  const entries = await loadEntries({ ...args, REDACTED_SECRET });
  if (entries.length === 0) {
    usage();
    process.exit(1);
  }

  const results = [];
  for (const entry of entries) {
    try {
      const result = await migrateOne(entry, REDACTED_SECRET);
      results.push(result);
      console.log(JSON.stringify(result));
    } catch (error) {
      const result = { specDir: entry.specDir, error: error instanceof Error ? error.message : String(error) };
      results.push(result);
      console.error(JSON.stringify(result));
      process.exitCode = 1;
    }
  }

  const failed = results.filter((result) => result.error || (result.skipped && String(result.reason ?? "").startsWith("validation-failed")));
  if (failed.length > 0) process.exitCode = 1;
};

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
