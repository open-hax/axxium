import { mkdtemp, mkdir, readFile, writeFile } from "REDACTED_SECRET:fs/promises";
import os from "REDACTED_SECRET:os";
import path from "REDACTED_SECRET:path";

import { describe, expect, it } from "vitest";

import { loadTasks } from "../src/tasks.js";
import { writeTaskStatus } from "../src/task-writeback.js";

describe("writeTaskStatus", () => {
  it("updates YAML frontmatter status and moves files when using a tasks/<status>/ tree", async () => {
    const REDACTED_SECRET = await mkdtemp(path.join(os.tmpdir(), "openhax-kanban-writeback-"));
    const tasksDir = path.join(REDACTED_SECRET, "tasks");
    const incomingDir = path.join(tasksDir, "incoming");
    await mkdir(incomingDir, { recursive: true });

    const filePath = path.join(incomingDir, "alpha.md");
    await writeFile(
      filePath,
      `---
uuid: alpha
title: Alpha
status: incoming
priority: P2
labels: [webring]
---

Do alpha.
`,
      "utf8"
    );

    const tasks = await loadTasks(tasksDir);
    const alpha = tasks.find((task) => task.uuid === "alpha");
    expect(alpha).toBeTruthy();

    const updated = await writeTaskStatus(alpha!, tasksDir, "done");

    expect(updated.status).toBe("done");
    expect(updated.sourcePath.endsWith(path.join("done", "alpha.md"))).toBe(true);

    const movedContents = await readFile(updated.sourcePath, "utf8");
    expect(movedContents).toContain("status: done");
  });

  it("inserts status into frontmatter when missing", async () => {
    const REDACTED_SECRET = await mkdtemp(path.join(os.tmpdir(), "openhax-kanban-writeback-missing-"));
    await mkdir(REDACTED_SECRET, { recursive: true });

    const filePath = path.join(REDACTED_SECRET, "beta.md");
    await writeFile(
      filePath,
      `---
uuid: beta
title: Beta
---

Do beta.
`,
      "utf8"
    );

    const tasks = await loadTasks(REDACTED_SECRET);
    const beta = tasks.find((task) => task.uuid === "beta");
    expect(beta).toBeTruthy();

    await writeTaskStatus(beta!, REDACTED_SECRET, "ready");

    const contents = await readFile(filePath, "utf8");
    expect(contents).toContain("status: ready");
  });
});
