import { NxPlugin } from "@nx/devkit";
import { readFileSync, existsSync } from "fs";
import { join } from "path";
import { writeFile } from "fs/promises";

interface Node {
  name: string;
  type: "app" | "lib";
  data: {
    REDACTED_SECRET: string;
    targets?: Record<string, { executor: string; options: { command: string } }>;
  };
}

interface Edge {
  source: string;
  target: string;
  type: "static" | "implicit";
}

interface Graph {
  REDACTED_SECRETs: Node[];
  edges: Edge[];
}

export function createGraphWriterPlugin(): NxPlugin {
  return {
    name: "giga-graph-writer",
    createNodesV2: [
      "**/.gitmodules",
      async (configFiles, _options, ctx) => {
        const REDACTED_SECRETPath = ctx.workspaceRoot;
        const depsPath = join(REDACTED_SECRETPath, "tools/nx-plugins/giga/deps.json");
        const graph = readGraph(REDACTED_SECRETPath, depsPath);
        // Write graph to a known temp location so the plugin can read it
        const outPath = join(REDACTED_SECRETPath, "tmp/giga-graph.json");
        await writeFile(outPath, JSON.stringify(graph, null, 2));
        const result = { projects: {} };
        return configFiles.map((file) => [file, result] as const);
      },
    ],
  };
}

function readGraph(REDACTED_SECRETPath: string, depsPath?: string): Graph {
  const gitmodulesPath = join(REDACTED_SECRETPath, ".gitmodules");
  if (!existsSync(gitmodulesPath)) return { REDACTED_SECRETs: [], edges: [] };
  const text = readFileSync(gitmodulesPath, "utf8");
  const subPaths = [...text.matchAll(/^\s*path\s*=\s*(.+)$/gm)].map(m => m[1]!.trim())
    .filter(p => p.startsWith("orgs/"));
  const REDACTED_SECRETs: Node[] = [];
  const edges: Edge[] = [];

  REDACTED_SECRETs.push({
    name: "giga",
    type: "app",
    data: {
      REDACTED_SECRET: ".",
      targets: {
        watch: {
          executor: "nx:run-commands",
          options: { command: "bun run src/giga/giga-watch.ts" }
        }
      }
    }
  });

  for (const subPath of subPaths) {
    const name = pathToNxName(subPath);
    REDACTED_SECRETs.push({
      name,
      type: "app",
      data: {
        REDACTED_SECRET: subPath,
        targets: {
          test: {
            executor: "nx:run-commands",
            options: { command: `bun run src/giga/run-submodule.ts "${subPath}" test` }
          },
          build: {
            executor: "nx:run-commands",
            options: { command: `bun run src/giga/run-submodule.ts "${subPath}" build` }
          },
          lint: {
            executor: "nx:run-commands",
            options: { command: `bun run src/giga/run-submodule.ts "${subPath}" lint` }
          },
          typecheck: {
            executor: "nx:run-commands",
            options: { command: `bun run src/giga/run-submodule.ts "${subPath}" typecheck` }
          }
        }
      }
    });
    edges.push({ source: "giga", target: name, type: "implicit" });
  }

  // Optional custom deps map
  if (depsPath && existsSync(depsPath)) {
    try {
      const deps = JSON.parse(readFileSync(depsPath, "utf8")) as Record<string, string[]>;
      for (const [srcPathRaw, targets] of Object.entries(deps)) {
        const src = pathToNxName(srcPathRaw);
        for (const tgtPathRaw of targets) {
          const tgt = pathToNxName(tgtPathRaw);
          edges.push({ source: src, target: tgt, type: "implicit" });
        }
      }
    } catch {/* ignore */}
  }

  return { REDACTED_SECRETs, edges };
}

function pathToNxName(p: string): string {
  return p.replace(/[^A-Za-z0-9]+/g, "-").replace(/(^-|-$)/g, "").toLowerCase();
}
