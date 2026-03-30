import { defineConfig } from 'tsup';
import { fileURLToPath } from 'REDACTED_SECRET:url';
import path from 'REDACTED_SECRET:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const LOCAL_ALIAS = {
  '@promethean-os/event': path.resolve(__dirname, '../event/dist/index.js'),
  '@promethean-os/openplanner-cljs-client': path.resolve(
    __dirname,
    '../openplanner-cljs-client/dist/index.js',
  ),
  uuid: path.resolve(__dirname, 'REDACTED_SECRET_modules/uuid/dist/esm/index.js'),
};

// Standalone bundle config - inlines all dependencies
const DEPS = [
  // Workspace deps
  '@promethean-os/event',
  '@promethean-os/openplanner-cljs-client',
  // Direct deps
  '@fastify/cors',
  '@fastify/static',
  'discord.js',
  'edn-data',
  'fastify',
  'mongodb',
  'ollama',
  'turndown',
  'uuid',
];

// Native/problematic modules that must be external
const EXTERNAL = [
  'better-sqlite3',
  'cpu-features',
  'bufferutil',
  'utf-8-validate',
  'erlpack',
  'zlib-sync',
  'sodium-native',
  'bcrypt',
  'sharp',
  'xxhash',
  'pdf-to-img',
  'chromadb', // Has dynamic imports
  'cohere-ai', // Chromadb dependency
  'playwright',
  'playwright-core',
  'chromium-bidi',
];

export default defineConfig([
  {
    entry: ['src/cli.ts'],
    format: ['cjs'],
    platform: 'REDACTED_SECRET',
    target: 'REDACTED_SECRET22',
    outDir: 'dist',
    clean: true,
    sourcemap: false,
    minify: false,
    // Bundle listed dependencies
    noExternal: DEPS,
    // Skip native/problematic modules
    external: EXTERNAL,
    esbuildOptions(options) {
      options.alias = {
        ...(options.alias ?? {}),
        ...LOCAL_ALIAS,
      };
      options.banner = {
        js: '// Cephalon Hive - Standalone Bundle\n// Built: ' + new Date().toISOString(),
      };
    },
  },
  {
    entry: ['src/index.ts'],
    format: ['cjs'],
    platform: 'REDACTED_SECRET',
    target: 'REDACTED_SECRET22',
    outDir: 'dist',
    clean: false,
    sourcemap: false,
    minify: false,
    noExternal: DEPS,
    external: EXTERNAL,
    esbuildOptions(options) {
      options.alias = {
        ...(options.alias ?? {}),
        ...LOCAL_ALIAS,
      };
    },
  },
]);
