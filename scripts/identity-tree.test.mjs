import { test } from 'node:test';
import assert from 'node:assert/strict';
import { treeRows, parsePermissions, policyFromFields, fieldsFromPolicy } from '../resources/public/identity-tree.js';

test('tree rows are depth-first from the visible root', () => {
  const rows = treeRows([
    { did: 'a:x:y', parent: 'a:x' }, { did: 'a', parent: 'hidden' }, { did: 'a:x', parent: 'a' }, { did: 'a:b', parent: 'a' },
  ]);
  assert.deepEqual(rows.map(r => [r.did, r.depth]), [['a', 0], ['a:b', 1], ['a:x', 1], ['a:x:y', 2]]);
});

test('policy fields round-trip', () => {
  const p = { repos: ['o/a', 'o/b'], permissions: { contents: 'write', issues: 'read' },
              actions: ['read', 'push'], forbid: ['force-push'] };
  assert.deepEqual(policyFromFields(fieldsFromPolicy(p)), p);
  assert.deepEqual(parsePermissions('contents=write,, bad, issues=read'), { contents: 'write', issues: 'read' });
});

