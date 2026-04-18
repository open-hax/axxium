import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "REDACTED_SECRET",
    include: ["tests/**/*.test.ts"],
    globals: false,
  },
});
