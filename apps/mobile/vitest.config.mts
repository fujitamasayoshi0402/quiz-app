import { defineConfig } from "vitest/config";

// React Native の部品は描かない。ログインの状態の扱いのような、画面に依らない性質だけを確かめる
export default defineConfig({
  resolve: { tsconfigPaths: true },
  test: { include: ["src/**/*.test.ts"] },
});
