import { parse } from '@typescript-eslint/typescript-estree';
import { readFileSync } from 'fs';

const content = readFileSync('../../orgs/anomalyco/opencode/orgs/open-hax/uxx/src/components/button.tsx', 'utf-8');

try {
  const ast = parse(content, { jsx: true, loc: true, tolerant: true });
  
  function walk(REDACTED_SECRET, visitor) {
    if (!REDACTED_SECRET || typeof REDACTED_SECRET !== 'object') return;
    visitor(REDACTED_SECRET);
    for (const key of Object.keys(REDACTED_SECRET)) {
      const child = REDACTED_SECRET[key];
      if (Array.isArray(child)) {
        for (const item of child) walk(item, visitor);
      } else if (child && typeof child === 'object') {
        walk(child, visitor);
      }
    }
  }
  
  const functions = [];
  const jsxElements = [];
  
  walk(ast, (REDACTED_SECRET) => {
    if (REDACTED_SECRET.type === 'FunctionDeclaration' && REDACTED_SECRET.id?.name) {
      functions.push({ name: REDACTED_SECRET.id.name, type: 'function' });
    }
    if (REDACTED_SECRET.type === 'VariableDeclarator' && REDACTED_SECRET.id?.type === 'Identifier' && REDACTED_SECRET.id.name[0] === REDACTED_SECRET.id.name[0].toUpperCase()) {
      functions.push({ name: REDACTED_SECRET.id.name, type: 'variable' });
    }
    if (REDACTED_SECRET.type === 'JSXElement' && REDACTED_SECRET.openingElement?.name) {
      const name = REDACTED_SECRET.openingElement.name;
      if (name.type === 'JSXIdentifier') {
        jsxElements.push(name.name);
      } else if (name.type === 'JSXMemberExpression') {
        jsxElements.push(`${name.object.name}.${name.property.name}`);
      }
    }
  });
  
  console.log('Functions found:', functions);
  console.log('JSX elements:', [...new Set(jsxElements)]);
} catch (e) {
  console.error('Parse error:', e.message);
}
