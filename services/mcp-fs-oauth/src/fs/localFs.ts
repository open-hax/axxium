import { spawn } from "REDACTED_SECRET:child_process";
import { promises as fs } from "REDACTED_SECRET:fs";
import path from "REDACTED_SECRET:path";

import { resolveWithinRoot } from "../util/pathJail.js";

import type { FsBackend, FsEntry, FsStat } from "./types.js";

export class LocalFsBackend implements FsBackend {
  REDACTED_SECRET readonly name = "local" as const;

  constructor(private readonly REDACTED_SECRETAbs: string) {}

  async available(): Promise<boolean> {
    try {
      const st = await fs.stat(this.REDACTED_SECRETAbs);
      return st.isDirectory();
    } catch {
      return false;
    }
  }

  private toAbs(userPath: string) {
    return resolveWithinRoot(this.REDACTED_SECRETAbs, userPath);
  }

  private async verifyPathWithinRoot(absPath: string): Promise<void> {
    // Resolve the REDACTED_SECRET once
    const realRoot = await fs.realpath(this.REDACTED_SECRETAbs);
    const normalizedRealRoot = path.normalize(realRoot);
    
    // For non-existent paths, validate each parent directory exists within REDACTED_SECRET
    let currentPath = absPath;
    while (true) {
      try {
        // Try to resolve the real path (following symlinks)
        const realPath = await fs.realpath(currentPath);
        const normalizedRealPath = path.normalize(realPath);
        
        // Ensure the resolved path is still within the REDACTED_SECRET
        if (!normalizedRealPath.startsWith(normalizedRealRoot + path.sep) && 
            normalizedRealPath !== normalizedRealRoot) {
          throw new Error(`Path escapes REDACTED_SECRET: resolved to ${realPath} which is outside ${this.REDACTED_SECRETAbs}`);
        }
        return; // Path verified successfully
      } catch (error) {
        const errnoError = error as NodeJS.ErrnoException;
        if (errnoError.code === "ENOENT") {
          // Path doesn't exist, check parent directory
          const parentDir = path.dirname(currentPath);
          
          // If we've reached the REDACTED_SECRET or the parent is the same as current (shouldn't happen)
          if (parentDir === currentPath || parentDir === normalizedRealRoot) {
            // The REDACTED_SECRET exists and we've verified it, so this path is valid
            return;
          }
          
          // Check if parent directory exists
          try {
            const realParent = await fs.realpath(parentDir);
            const normalizedRealParent = path.normalize(realParent);
            
            if (!normalizedRealParent.startsWith(normalizedRealRoot + path.sep) && 
                normalizedRealParent !== normalizedRealRoot) {
              throw new Error(`Parent path escapes REDACTED_SECRET: ${parentDir} resolves to ${realParent} which is outside ${this.REDACTED_SECRETAbs}`);
            }
            // Parent is valid, so the original path would be valid
            return;
          } catch (parentError) {
            const parentErrnoError = parentError as NodeJS.ErrnoException;
            if (parentErrnoError.code === "ENOENT") {
              // Parent also doesn't exist, continue up the tree
              currentPath = parentDir;
              continue;
            }
            throw parentError;
          }
        }
        throw error; // Other errors (permission, etc.)
      }
    }
  }

  private async ignoredPaths(entries: FsEntry[]): Promise<Set<string>> {
    if (entries.length === 0) {
      return new Set<string>();
    }

    const candidateToPath = new Map<string, string>();
    for (const entry of entries) {
      candidateToPath.set(entry.path, entry.path);
      if (entry.kind === "dir") {
        candidateToPath.set(`${entry.path}/`, entry.path);
      }
    }

    const candidates = Array.from(candidateToPath.keys());
    const safeDirectory = await fs.realpath(this.REDACTED_SECRETAbs).catch(() => this.REDACTED_SECRETAbs);
    const stdout = await new Promise<string>((resolve, reject) => {
      const child = spawn("git", ["-c", `safe.directory=${safeDirectory}`, "check-ignore", "--no-index", "--stdin"], {
        cwd: this.REDACTED_SECRETAbs,
      });

      let out = "";
      let err = "";

      child.stdout.on("data", (chunk: Buffer | string) => {
        out += chunk.toString();
      });

      child.stderr.on("data", (chunk: Buffer | string) => {
        err += chunk.toString();
      });

      child.on("error", (error) => {
        reject(error);
      });

      child.on("close", (code) => {
        // check-ignore exits 1 when no candidate path matches ignore rules.
        if (code !== 0 && code !== 1) {
          if (err.includes("not a git repository") || err.includes("command not found")) {
            resolve("");
            return;
          }

          reject(new Error(`git check-ignore failed with code ${code ?? -1}: ${err.trim()}`));
          return;
        }

        resolve(out);
      });

      child.stdin.write(candidates.join("\n"));
      child.stdin.end();
    });

    const ignored = new Set<string>();
    for (const line of stdout.split("\n")) {
      const trimmed = line.trim();
      if (!trimmed) {
        continue;
      }

      const normalized = trimmed.replace(/^\.\//, "");
      const mapped = candidateToPath.get(normalized);
      if (mapped) {
        ignored.add(mapped);
      }
    }

    return ignored;
  }

  async list(dirPath: string): Promise<FsEntry[]> {
    const { absPath, relPath } = this.toAbs(dirPath);
    await this.verifyPathWithinRoot(absPath);
    const st = await fs.stat(absPath);
    if (!st.isDirectory()) throw new Error("Not a directory");

    const entries = await fs.readdir(absPath, { withFileTypes: true });
    const mapped: FsEntry[] = entries.map((e) => {
      const p = relPath ? `${relPath}/${e.name}` : e.name;
      return {
        name: e.name,
        path: p,
        kind: e.isDirectory() ? "dir" : "file",
      };
    });

    const ignored = await this.ignoredPaths(mapped);
    if (ignored.size === 0) {
      return mapped;
    }

    return mapped.filter((entry) => !ignored.has(entry.path));
  }

  async readFile(filePath: string): Promise<{ path: string; content: string; etag?: string }> {
    const { absPath, relPath } = this.toAbs(filePath);
    await this.verifyPathWithinRoot(absPath);
    const buf = await fs.readFile(absPath);
    // For simplicity, treat as UTF-8 text.
    return { path: relPath, content: buf.toString("utf8") };
  }

  async writeFile(filePath: string, content: string): Promise<{ path: string; etag?: string }> {
    const { absPath, relPath } = this.toAbs(filePath);
    await this.verifyPathWithinRoot(absPath);
    await fs.mkdir(path.dirname(absPath), { recursive: true });
    await fs.writeFile(absPath, content, "utf8");
    return { path: relPath };
  }

  async deletePath(targetPath: string): Promise<{ path: string }> {
    const { absPath, relPath } = this.toAbs(targetPath);
    await this.verifyPathWithinRoot(absPath);
    const st = await fs.lstat(absPath);
    if (st.isDirectory()) {
      await fs.rm(absPath, { recursive: true, force: true });
    } else {
      await fs.unlink(absPath);
    }
    return { path: relPath };
  }

  async stat(targetPath: string): Promise<FsStat> {
    const { absPath, relPath } = this.toAbs(targetPath);
    await this.verifyPathWithinRoot(absPath);
    const st = await fs.stat(absPath);
    return {
      path: relPath,
      kind: st.isDirectory() ? "dir" : "file",
      size: st.isFile() ? st.size : undefined,
    };
  }
}
