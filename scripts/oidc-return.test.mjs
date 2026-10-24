import { test } from 'node:test';
import assert from 'node:assert/strict';
import { safeInteractionReturn } from '../resources/public/oidc-return.js';

test('only exact interaction paths can resume shared login', () => {
  assert.equal(safeInteractionReturn('/oidc/interaction/aB_12-Z'), '/oidc/interaction/aB_12-Z');
  for (const value of [null, {}, '', '//evil.test', 'https://evil.test/oidc/interaction/x',
    'javascript:alert(1)', '/oidc/interaction/x?next=https://evil.test',
    '/oidc/interaction/%2f%2fevil.test', '/oidc/interaction/../logout',
    '/oidc/interaction/x#fragment', `/oidc/interaction/${'a'.repeat(129)}`]) {
    assert.equal(safeInteractionReturn(value), null);
  }
});
