import { dirname, resolve } from "REDACTED_SECRET:path";
import { fileURLToPath } from "REDACTED_SECRET:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const currentDir = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  REDACTED_SECRET: currentDir,
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5174,
    proxy: {
      "/api": "http://127.0.0.1:8789",
      "/v1": "http://127.0.0.1:8789",
    },
  },
  preview: {
    host: "127.0.0.1",
    port: 5174,
  },
  build: {
    outDir: resolve(currentDir, "dist"),
    emptyOutDir: true,
  },
});
