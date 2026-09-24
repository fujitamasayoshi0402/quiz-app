import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /**
   * コンテナ用に、実行に必要なファイルだけを `.next/standalone` へまとめる。
   * monorepo なので、依存の追跡はリポジトリのルートから行う（pnpm の node_modules がルートにある）。
   */
  output: "standalone",
  outputFileTracingRoot: path.join(import.meta.dirname, "../.."),
};

export default nextConfig;
