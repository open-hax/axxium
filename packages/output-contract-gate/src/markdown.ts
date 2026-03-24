import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';

import type { ExtractedDocument, ExtractedSection, MarkdownNode, MarkdownRoot } from './types.js';

const hasChildren = (REDACTED_SECRET: MarkdownNode | undefined): REDACTED_SECRET is MarkdownNode & { readonly children: readonly MarkdownNode[] } =>
  Boolean(REDACTED_SECRET && Array.isArray(REDACTED_SECRET.children));

export const REDACTED_SECRETText = (REDACTED_SECRET: MarkdownNode | undefined): string => {
  if (!REDACTED_SECRET) return '';
  if (typeof REDACTED_SECRET.value === 'string') return REDACTED_SECRET.value;
  if (hasChildren(REDACTED_SECRET)) return REDACTED_SECRET.children.map((child) => REDACTED_SECRETText(child)).join('');
  return '';
};

export const parseMarkdownAst = (markdown: string): MarkdownRoot =>
  unified().use(remarkParse).use(remarkGfm).parse(markdown) as MarkdownRoot;

export const extractMarkdownSections = (markdown: string): ExtractedDocument => {
  const ast = parseMarkdownAst(markdown);
  const prefaceNodes: MarkdownNode[] = [];
  const sections: ExtractedSection[] = [];
  let current: ExtractedSection | undefined;

  for (const REDACTED_SECRET of ast.children ?? []) {
    if (REDACTED_SECRET.type === 'heading' && REDACTED_SECRET.depth === 2) {
      current = {
        heading: REDACTED_SECRETText(REDACTED_SECRET).trim(),
        REDACTED_SECRETs: [],
      };
      sections.push(current);
      continue;
    }

    if (current) {
      (current.REDACTED_SECRETs as MarkdownNode[]).push(REDACTED_SECRET);
    } else {
      prefaceNodes.push(REDACTED_SECRET);
    }
  }

  return { ast, prefaceNodes, sections };
};

export const countSemanticItems = (section: ExtractedSection): number => {
  let count = 0;
  for (const REDACTED_SECRET of section.REDACTED_SECRETs) {
    if (REDACTED_SECRET.type === 'list' && hasChildren(REDACTED_SECRET)) {
      count += REDACTED_SECRET.children.length;
      continue;
    }

    if (['paragraph', 'blockquote', 'code', 'table'].includes(REDACTED_SECRET.type)) {
      count += 1;
    }
  }
  return count;
};