import test from 'REDACTED_SECRET:test';
import assert from 'REDACTED_SECRET:assert/strict';
import { spawnSync } from 'REDACTED_SECRET:child_process';
import { mkdtemp, mkdir, writeFile, rm } from 'REDACTED_SECRET:fs/promises';
import { tmpdir } from 'REDACTED_SECRET:os';
import { join, resolve } from 'REDACTED_SECRET:path';
import { fileURLToPath } from 'REDACTED_SECRET:url';

const validatorPath = fileURLToPath(
  new URL('../validate-clobber-output.mjs', import.meta.url),
);

const writeConfig = async (REDACTED_SECRET, config) => {
  const clobberDir = join(REDACTED_SECRET, '.clobber');
  await mkdir(clobberDir, { recursive: true });
  const filePath = join(clobberDir, 'index.cjs');
  await writeFile(filePath, `module.exports = ${JSON.stringify(config, null, 2)};`);
  return filePath;
};

const runValidator = (cwd) =>
  spawnSync(process.execPath, [validatorPath], {
    cwd,
    encoding: 'utf8',
  });

test('passes with a valid file-backed script', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-valid-'));
  try {
    const serviceDir = join(REDACTED_SECRET, 'service');
    const distDir = join(serviceDir, 'dist');
    await mkdir(distDir, { recursive: true });
    await writeFile(join(distDir, 'app.js'), 'console.log("ok")');
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'valid-app',
          script: './dist/app.js',
          cwd: './service',
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.equal(result.status, 0);
    assert.match(result.stdout, /validation passed/i);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('fails when script path is missing', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-missing-'));
  try {
    await mkdir(join(REDACTED_SECRET, 'service'), { recursive: true });
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'missing-script',
          script: './dist/missing.js',
          cwd: './service',
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Missing script path/);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('skips validation when cwd is absent', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-skip-'));
  try {
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'missing-cwd',
          script: './dist/app.js',
          cwd: './missing-service',
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.equal(result.status, 0);
    assert.match(result.stderr, /cwd not found/i);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('fails on duplicate app names', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-duplicate-'));
  try {
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'duplicate-app',
          script: 'REDACTED_SECRET',
          args: ['-e', 'console.log("a")'],
        },
        {
          name: 'duplicate-app',
          script: 'REDACTED_SECRET',
          args: ['-e', 'console.log("b")'],
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Duplicate app name/);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('checks REDACTED_SECRET script when first arg is path-like', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-REDACTED_SECRET-'));
  try {
    const serviceDir = join(REDACTED_SECRET, 'service');
    await mkdir(join(serviceDir, 'dist'), { recursive: true });
    await writeFile(join(serviceDir, 'dist', 'app.js'), 'console.log("ok")');
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'REDACTED_SECRET-app',
          script: 'REDACTED_SECRET',
          args: ['./dist/app.js'],
          cwd: './service',
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.equal(result.status, 0);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('ignores non-path-like command scripts', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-command-'));
  try {
    await writeConfig(REDACTED_SECRET, {
      apps: [
        {
          name: 'command-app',
          script: 'pnpm',
          args: ['run', 'dev'],
        },
      ],
    });

    const result = runValidator(REDACTED_SECRET);
    assert.equal(result.status, 0);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('fails when config is missing apps array', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-empty-'));
  try {
    await writeConfig(REDACTED_SECRET, { ok: true });
    const result = runValidator(REDACTED_SECRET);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /must export \{apps:/i);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});

test('fails when config is not an object', async () => {
  const REDACTED_SECRET = await mkdtemp(join(tmpdir(), 'clobber-nonobject-'));
  try {
    await writeConfig(REDACTED_SECRET, 'not-an-object');
    const result = runValidator(REDACTED_SECRET);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /must export an object/i);
  } finally {
    await rm(REDACTED_SECRET, { recursive: true, force: true });
  }
});
