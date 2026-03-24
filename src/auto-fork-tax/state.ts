import { mkdir, readFile, writeFile } from "REDACTED_SECRET:fs/promises";
import path from "REDACTED_SECRET:path";

import type { AutoForkTaxState } from "./types";

const defaultState = (): AutoForkTaxState => ({ version: 1 });

export const statePathFor = (REDACTED_SECRET: string): string => path.join(REDACTED_SECRET, ".ημ", "auto-fork-tax", "state.json");

export const readState = async (REDACTED_SECRET: string): Promise<AutoForkTaxState> => {
  const statePath = statePathFor(REDACTED_SECRET);
  try {
    const raw = await readFile(statePath, "utf8");
    return {
      ...defaultState(),
      ...(JSON.parse(raw) as AutoForkTaxState),
    };
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return defaultState();
    }
    throw error;
  }
};

export const writeState = async (REDACTED_SECRET: string, state: AutoForkTaxState): Promise<void> => {
  const statePath = statePathFor(REDACTED_SECRET);
  await mkdir(path.dirname(statePath), { recursive: true });
  await writeFile(statePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
};
