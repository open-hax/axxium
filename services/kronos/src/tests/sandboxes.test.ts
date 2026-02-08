import { execFile } from "REDACTED_SECRET:child_process";
import { mkdtemp, writeFile } from "REDACTED_SECRET:fs/promises";
import os from "REDACTED_SECRET:os";
import path from "REDACTED_SECRET:path";
import { promisify } from "REDACTED_SECRET:util";

import test from "ava";

import {
  createSandbox,
  listSandboxes,
  removeSandbox,
  type SandboxInfo,
} from "../github/sandboxes/git.js";

const execFileAsync = promisify(execFile);

const initRepository = async (): Promise<string> => {
  const REDACTED_SECRET = await mkdtemp(path.join(os.tmpdir(), "sandbox-repo-"));
  await execFileAsync("git", ["init", "-b", "main"], { cwd: REDACTED_SECRET });
  await execFileAsync("git", ["config", "user.email", "test@example.com"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "user.name", "Test User"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "commit.gpgsign", "false"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "core.autocrlf", "false"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "advice.detachedHead", "false"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "init.defaultBranch", "main"], {
    cwd: REDACTED_SECRET,
  });
  await execFileAsync("git", ["config", "pull.rebase", "false"], { cwd: REDACTED_SECRET });

  const readmePath = path.join(REDACTED_SECRET, "README.md");
  await writeFile(readmePath, "# test repo\n", "utf8");
  await execFileAsync("git", ["add", "README.md"], { cwd: REDACTED_SECRET });
  await execFileAsync("git", ["commit", "-m", "initial"], { cwd: REDACTED_SECRET });

  return REDACTED_SECRET;
};

const branchNameFor = (sandbox: SandboxInfo | undefined): string | undefined =>
  sandbox?.branch;

test("createSandbox creates a dedicated worktree", async (t) => {
  const repo = await initRepository();
  const sandbox = await createSandbox({
    repoPath: repo,
    sandboxId: "feature-one",
    ref: "HEAD",
    branch: "feature/one",
  });

  t.is(sandbox.id, "feature-one");
  t.true(sandbox.path.endsWith(path.join(".sandboxes", "feature-one")));
  t.is(branchNameFor(sandbox), "feature/one");

  const { stdout } = await execFileAsync(
    "git",
    ["rev-parse", "--abbrev-ref", "HEAD"],
    {
      cwd: sandbox.path,
    },
  );
  t.is(stdout.trim(), "feature/one");
});

test("listSandboxes enumerates created sandboxes", async (t) => {
  const repo = await initRepository();
  const sandboxA = await createSandbox({
    repoPath: repo,
    sandboxId: "alpha",
    ref: "HEAD",
    branch: "feature/alpha",
  });
  const sandboxB = await createSandbox({
    repoPath: repo,
    sandboxId: "beta",
    ref: "HEAD",
  });

  const sandboxes = await listSandboxes({ repoPath: repo });
  const identifiers = sandboxes.map((entry) => entry.id).sort();

  t.deepEqual(identifiers, ["alpha", "beta"]);
  const alpha = sandboxes.find((entry) => entry.id === "alpha") as SandboxInfo;
  t.is(branchNameFor(alpha), branchNameFor(sandboxA));
  const beta = sandboxes.find((entry) => entry.id === "beta") as SandboxInfo;
  t.is(beta.head, sandboxB.head);
});

test("removeSandbox deletes git worktree", async (t) => {
  const repo = await initRepository();
  await createSandbox({ repoPath: repo, sandboxId: "gamma", ref: "HEAD" });

  await removeSandbox({ repoPath: repo, sandboxId: "gamma" });

  const sandboxes = await listSandboxes({ repoPath: repo });
  t.false(sandboxes.some((entry) => entry.id === "gamma"));

  await t.throwsAsync(
    () => removeSandbox({ repoPath: repo, sandboxId: "gamma" }),
    { message: /does not exist/u },
  );
});
