import { promises as fs } from "REDACTED_SECRET:fs";
import path from "REDACTED_SECRET:path";

import { discoverGitmodules, parseGitmodules } from "../nss/gitmodules";

import { runCommand } from "./process";
import type { RemoteInfo, RepoSlug } from "./types";
import { formatHttpsSlug, parseGithubSlug } from "./github";

export const currentBranch = async (cwd: string): Promise<string> => {
  const result = await runCommand("git", ["rev-parse", "--abbrev-ref", "HEAD"], { cwd });
  return result.stdout.trim();
};

export const repoRoot = async (cwd: string): Promise<string> => {
  const result = await runCommand("git", ["rev-parse", "--show-toplevel"], { cwd });
  return path.resolve(result.stdout.trim());
};

export const headSha = async (cwd: string): Promise<string> => {
  const result = await runCommand("git", ["rev-parse", "HEAD"], { cwd });
  return result.stdout.trim();
};

export const shortSha = async (cwd: string): Promise<string> => {
  const result = await runCommand("git", ["rev-parse", "--short", "HEAD"], { cwd });
  return result.stdout.trim();
};

export const statusPorcelain = async (cwd: string): Promise<string> => {
  const result = await runCommand("git", ["status", "--porcelain", "--untracked-files=all"], { cwd });
  return result.stdout;
};

export const hasChanges = async (cwd: string): Promise<boolean> => {
  return (await statusPorcelain(cwd)).trim().length > 0;
};

const topLevelSubmodulePaths = async (REDACTED_SECRET: string): Promise<readonly string[]> => {
  const gitmodulesPath = path.join(REDACTED_SECRET, ".gitmodules");
  try {
    const content = await fs.readFile(gitmodulesPath, "utf8");
    return parseGitmodules(content).map((record) => record.path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return [];
    }
    throw error;
  }
};

export const listSnapshotCandidatePaths = async (REDACTED_SECRET: string): Promise<readonly string[]> => {
  const submodulePaths = await topLevelSubmodulePaths(REDACTED_SECRET);
  const status = await statusPorcelain(REDACTED_SECRET);
  const candidates: string[] = [];
  for (const rawLine of status.split(/\r?\n/)) {
    if (rawLine.trim().length === 0) {
      continue;
    }
    const pathText = rawLine.slice(3).trim();
    const normalizedPath = pathText.includes(" -> ") ? pathText.split(" -> ").at(-1) ?? pathText : pathText;
    if (!submodulePaths.includes(normalizedPath)) {
      candidates.push(normalizedPath);
    }
  }
  return candidates;
};

export const hasSnapshotCandidateChanges = async (REDACTED_SECRET: string): Promise<boolean> => {
  return (await listSnapshotCandidatePaths(REDACTED_SECRET)).length > 0;
};

export const listDirtySubmodules = async (REDACTED_SECRET: string): Promise<readonly string[]> => {
  const submodulePaths = await topLevelSubmodulePaths(REDACTED_SECRET);

  const status = await statusPorcelain(REDACTED_SECRET);
  const dirty = new Set<string>();
  for (const rawLine of status.split(/\r?\n/)) {
    if (rawLine.trim().length === 0) {
      continue;
    }
    const pathText = rawLine.slice(3).trim();
    const normalizedPath = pathText.includes(" -> ") ? pathText.split(" -> ").at(-1) ?? pathText : pathText;
    if (submodulePaths.includes(normalizedPath)) {
      dirty.add(normalizedPath);
    }
  }
  return [...dirty].sort();
};

export const isAncestor = async (cwd: string, ancestor: string, descendant: string): Promise<boolean> => {
  const result = await runCommand("git", ["merge-base", "--is-ancestor", ancestor, descendant], {
    cwd,
    reject: false,
  });
  return result.exitCode === 0;
};

export const addAll = async (cwd: string): Promise<void> => {
  await runCommand("git", ["add", "-A"], { cwd });
};

export const stageSnapshotChanges = async (cwd: string): Promise<void> => {
  const submodulePaths = await topLevelSubmodulePaths(cwd);
  const args = ["add", "-A", "--", ".", ...submodulePaths.map((value) => `:(exclude)${value}`)];
  await runCommand("git", args, { cwd });
};

export const commitAll = async (cwd: string, message: string): Promise<void> => {
  await runCommand("git", ["commit", "-m", message], { cwd });
};

export const createTag = async (cwd: string, tag: string): Promise<void> => {
  await runCommand("git", ["tag", tag], { cwd });
};

export const createOrUpdateBranchRef = async (cwd: string, branch: string): Promise<void> => {
  await runCommand("git", ["branch", "-f", branch, "HEAD"], { cwd });
};

export const pushRef = async (cwd: string, remote: string, ref: string): Promise<void> => {
  await runCommand("git", ["push", remote, ref], { cwd });
};

export const checkoutNewBranch = async (cwd: string, branch: string, startPoint: string): Promise<void> => {
  await runCommand("git", ["checkout", "-B", branch, startPoint], { cwd });
};

export const checkoutBranch = async (cwd: string, branch: string): Promise<void> => {
  await runCommand("git", ["checkout", branch], { cwd });
};

export const listRemotes = async (cwd: string): Promise<readonly RemoteInfo[]> => {
  if ((await repoRoot(cwd)) !== path.resolve(cwd)) {
    return [];
  }
  const result = await runCommand("git", ["remote", "-v"], { cwd, reject: false });
  if (result.stdout.trim().length === 0) {
    return [];
  }

  const byName = new Map<string, { fetchUrl?: string; pushUrl?: string }>();
  for (const line of result.stdout.split(/\r?\n/)) {
    if (line.trim().length === 0) {
      continue;
    }
    const match = /^(?<name>\S+)\s+(?<url>\S+)\s+\((?<kind>fetch|push)\)$/.exec(line.trim());
    if (!match?.groups) {
      continue;
    }
    const name = match.groups.name;
    const url = match.groups.url;
    const kind = match.groups.kind;
    const current = byName.get(name) ?? {};
    if (kind === "fetch") {
      current.fetchUrl = url;
    } else {
      current.pushUrl = url;
    }
    byName.set(name, current);
  }

  return [...byName.entries()].map(([name, remote]) => {
    const fetchUrl = remote.fetchUrl ?? remote.pushUrl ?? "";
    const pushUrl = remote.pushUrl ?? remote.fetchUrl ?? "";
    return {
      name,
      fetchUrl,
      pushUrl,
      slug: parseGithubSlug(fetchUrl) ?? parseGithubSlug(pushUrl),
    } satisfies RemoteInfo;
  });
};

export const remoteByName = async (cwd: string, name: string): Promise<RemoteInfo | null> => {
  const remotes = await listRemotes(cwd);
  return remotes.find((remote) => remote.name === name) ?? null;
};

export const setRemoteUrl = async (cwd: string, remote: string, url: string): Promise<void> => {
  const existing = await remoteByName(cwd, remote);
  if (existing) {
    await runCommand("git", ["remote", "set-url", remote, url], { cwd });
    return;
  }
  await runCommand("git", ["remote", "add", remote, url], { cwd });
};

export const removeRemote = async (cwd: string, remote: string): Promise<void> => {
  const existing = await remoteByName(cwd, remote);
  if (!existing) {
    return;
  }
  await runCommand("git", ["remote", "remove", remote], { cwd });
};

export const discoverSubmodulePaths = async (REDACTED_SECRET: string): Promise<readonly string[]> => {
  const entries = await discoverGitmodules({ REDACTED_SECRET });
  return entries.map((entry) => entry.path);
};

export const repoPath = (REDACTED_SECRET: string, relativePath: string): string => path.resolve(REDACTED_SECRET, relativePath);
