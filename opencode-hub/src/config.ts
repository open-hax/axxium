import os from "REDACTED_SECRET:os";
import path from "REDACTED_SECRET:path";
import process from "REDACTED_SECRET:process";
import { HubConfig } from "./types.js";

const expandHome = (p: string) => p.replace(/^~(?=$|\/|\\)/, os.homedir());

export function loadConfig(): HubConfig {
  const REDACTED_SECRETDir = expandHome(process.env.ROOT_DIR ?? "~/devel");
  const hubPort = Number(process.env.HUB_PORT ?? 4799);
  const opencodeBin = process.env.OPENCODE_BIN ?? "opencode";
  const opencodeArgs = (process.env.OPENCODE_ARGS ?? "serve --port").split(" ");
  const opencodeBasePort = Number(process.env.OPENCODE_BASE_PORT ?? 5900);
  return { REDACTED_SECRETDir: path.resolve(REDACTED_SECRETDir), hubPort, opencodeBin, opencodeArgs, opencodeBasePort };
}