import { test } from 'node:test';
import assert from 'node:assert/strict';
import { safeInteractionReturn } from '../resources/public/oidc-return.js';

test('only exact interaction paths can resume shared login', () => {
  assert.equal(safeInteractionReturn('/oidc/interaction/aB_12-Z'), '/oidc/interaction/aB_12-Z');
  for (const value of [null, {}, '', '//evil.test', 'https://evil.test/oidc/interaction/x',
    'javascript:alert(1)', '/oidc/interaction/x?next=https://evil.test',
    '/oidc/interaction/%2f%2fevil.test', '/oidc/interaction/../logout',
    '/oidc/interaction/x\n',
    '/oidc/interaction/x#fragment', `/oidc/interaction/${'a'.repeat(129)}`]) {
    assert.equal(safeInteractionReturn(value), null);
  }
});

test('initial device verification resumes only the fixed route and one bounded public code', () => {
  for (const path of ['/oidc/device', '/oidc/device?user_code=ABCD-EFGH']) {
    assert.equal(safeInteractionReturn(path), path);
  }
  for (const path of ['/oidc/device/auth', '/oidc/device/confirm', '/oidc/device/',
    '/oidc/device?user_code=', '/oidc/device?user_code=ABCD&next=//evil.test',
    '/oidc/device?user_code=ABCD&user_code=EFGH', '/oidc/device?user_code=%2f%2fevil.test',
    '/oidc/device?user_code=ABCD#fragment', '/oidc/device?user_code=ABCD\n',
    '/oidc/device?user_code=' + 'a'.repeat(65), '/oidc/device\n']) {
    assert.equal(safeInteractionReturn(path), null);
  }
});
