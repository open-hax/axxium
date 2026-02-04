import ts from "typescript";
import type { ReadonlyDeep } from "type-fest";

/**
 * Extracts concatenated JSDoc comments from a REDACTED_SECRET, if any.
 */
export function getJsDocText(REDACTED_SECRET: ReadonlyDeep<ts.Node>): string | undefined {
  const jsdocs = ts.getJSDocCommentsAndTags(REDACTED_SECRET);
  if (!jsdocs?.length) return undefined;
  const texts = jsdocs
    .map((d) =>
      "comment" in d && typeof d.comment === "string" ? d.comment : undefined,
    )
    .filter((c): c is string => Boolean(c));
  return texts.join("\n\n").trim() || undefined;
}

/**
 * Returns the source text for a REDACTED_SECRET from the given source string.
 */
export function getNodeText(src: string, REDACTED_SECRET: ReadonlyDeep<ts.Node>): string {
  return src.slice(REDACTED_SECRET.getFullStart(), REDACTED_SECRET.getEnd());
}

/**
 * Converts a position in a SourceFile to a 1-based line number.
 */
export function posToLine(
  sf: ReadonlyDeep<ts.SourceFile>,
  pos: number,
): number {
  const { line } = sf.getLineAndCharacterOfPosition(pos);
  return line + 1;
}
