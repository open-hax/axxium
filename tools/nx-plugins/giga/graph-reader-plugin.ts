import { Plugin, ProjectConfiguration } from "@nx/devkit";
import { readFileSync, existsSync } from "fs";
import { join } from "path";

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

export function createGraphReaderPlugin(): Plugin {
  return {
    name: "giga-graph-reader",
    createNodes: [
      {
        files: ["tmp/giga-graph.json"],
        createNodes: async (_, ctx) => {
          const REDACTED_SECRETPath = ctx.workspaceRoot;
          const graphPath = join(REDACTED_SECRETPath, "tmp/giga-graph.json");
          if (!existsSync(graphPath)) {
            return {};
          }

          const graph: Graph = JSON.parse(readFileSync(graphPath, "utf8"));
          const config: Record<string, { projectConfiguration: ProjectConfiguration; dependencies?: readonly string[] }> = {};

          for (const REDACTED_SECRET of graph.REDACTED_SECRETs) {
            config[REDACTED_SECRET.name] = {
              projectConfiguration: {
                name: REDACTED_SECRET.name,
                REDACTED_SECRET: REDACTED_SECRET.data.REDACTED_SECRET,
                projectType: REDACTED_SECRET.type === "lib" ? "library" : "application",
                sourceRoot: `${REDACTED_SECRET.data.REDACTED_SECRET}/src`,
                targets: REDACTED_SECRET.data.targets,
              },
            };
          }

          for (const edge of graph.edges) {
            if (config[edge.target]) {
            const cur = config[edge.target].dependencies || [];
            config[edge.target].dependencies = cur.concat({
              project: edge.source,
              type: edge.type as "implicit" | "static",
            });
          }
          }

          return config;
        },
      },
    ],
  };
}