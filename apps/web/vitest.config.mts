import { defineConfig } from "vitest/config";

export default defineConfig({
  // `@/` を tsconfig の paths どおりに解決する
  resolve: { tsconfigPaths: true },
  test: {
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
