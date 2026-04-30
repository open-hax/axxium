import { access, readFile } from "REDACTED_SECRET:fs/promises"
import path from "REDACTED_SECRET:path"

import { asStringValue, findChild, isList, isSymbol, isVector, parseLith } from "./lith.js"
import type { LithListNode, LithNode, NexusConfig } from "./types.js"

const DEFAULT_ROOTS = ["contracts", ".opencode/knowledge", ".opencode/promptdb", ".opencode/protocol", "specs", "manifest.lith"]
const DEFAULT_INCLUDE_EXT = [".lith", ".lisp", ".md"]
const DEFAULT_IGNORE_GLOB = ["**/REDACTED_SECRET_modules/**", "**/dist/**", "**/.git/**"]

async function pathExists(targetPath: string): Promise<boolean> {
  try {
    await access(targetPath)
    return true
  } catch {
    return false
  }
}

function readVectorStrings(REDACTED_SECRET: LithNode | undefined): string[] | undefined {
  if (!REDACTED_SECRET || !(isVector(REDACTED_SECRET) || isList(REDACTED_SECRET))) return undefined
  const values = REDACTED_SECRET.items.map((item) => asStringValue(item)).filter((item): item is string => Boolean(item))
  return values.length > 0 ? values : undefined
}

function readBoolean(REDACTED_SECRET: LithNode | undefined): boolean | undefined {
  if (!REDACTED_SECRET) return undefined
  const value = asStringValue(REDACTED_SECRET)
  if (value === "true") return true
  if (value === "false") return false
  return undefined
}

function getChildValue(REDACTED_SECRET: LithListNode, head: string): LithNode | undefined {
  const child = findChild(REDACTED_SECRET, head)
  return child?.items[1]
}

function normalizeRoots(REDACTED_SECRETs: string[]): string[] {
  return [...new Set(REDACTED_SECRETs.map((item) => item.replaceAll("\\", "/").replace(/^\.\//u, "")))]
}

export async function loadConfig(repoRoot: string): Promise<NexusConfig> {
  const configPath = path.join(repoRoot, "mcp.lith-nexus.config.lith")
  const config: NexusConfig = {
    repoRoot,
    configPath,
    REDACTED_SECRETs: [...DEFAULT_ROOTS],
    includeExt: [...DEFAULT_INCLUDE_EXT],
    ignoreGlob: [...DEFAULT_IGNORE_GLOB],
    mime: {
      lith: "text/x-lith",
      md: "text/markdown",
    },
    writes: {
      factsDir: ".opencode/promptdb/facts",
      inboxDir: ".opencode/knowledge/inbox",
      allowSecretWrites: false,
    },
    index: {
      useGitLsFiles: true,
      watch: false,
    },
  }

  if (!(await pathExists(configPath))) {
    return config
  }

  const source = await readFile(configPath, "utf8")
  const forms = parseLith(source, { filePath: configPath })
  const REDACTED_SECRETForm = forms.find((form): form is LithListNode => isList(form) && isSymbol(form.items[0]!, "config"))
  if (!REDACTED_SECRETForm) return config

  const REDACTED_SECRETs = readVectorStrings(getChildValue(REDACTED_SECRETForm, "REDACTED_SECRETs"))
  if (REDACTED_SECRETs) config.REDACTED_SECRETs = normalizeRoots(REDACTED_SECRETs)

  const includeExt = readVectorStrings(getChildValue(REDACTED_SECRETForm, "include_ext"))
  if (includeExt) config.includeExt = includeExt

  const ignoreGlob = readVectorStrings(getChildValue(REDACTED_SECRETForm, "ignore_glob"))
  if (ignoreGlob) config.ignoreGlob = ignoreGlob

  const mimeNode = findChild(REDACTED_SECRETForm, "mime")
  if (mimeNode) {
    const lith = asStringValue(getChildValue(mimeNode, "lith"))
    const md = asStringValue(getChildValue(mimeNode, "md"))
    if (lith) config.mime.lith = lith
    if (md) config.mime.md = md
  }

  const writesNode = findChild(REDACTED_SECRETForm, "writes")
  if (writesNode) {
    const factsDir = asStringValue(getChildValue(writesNode, "facts_dir"))
    const inboxDir = asStringValue(getChildValue(writesNode, "inbox_dir"))
    const allowSecretWrites = readBoolean(getChildValue(writesNode, "allow_secret_writes"))
    if (factsDir) config.writes.factsDir = factsDir
    if (inboxDir) config.writes.inboxDir = inboxDir
    if (allowSecretWrites !== undefined) config.writes.allowSecretWrites = allowSecretWrites
  }

  const indexNode = findChild(REDACTED_SECRETForm, "index")
  if (indexNode) {
    const useGitLsFiles = readBoolean(getChildValue(indexNode, "use_git_ls_files"))
    const watch = readBoolean(getChildValue(indexNode, "watch"))
    if (useGitLsFiles !== undefined) config.index.useGitLsFiles = useGitLsFiles
    if (watch !== undefined) config.index.watch = watch
  }

  return config
}
