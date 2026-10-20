import { defineConfig } from "@playwright/test";

/**
 * README の画面（スクリーンショットと GIF）を撮る（DEV-180）。E2E と同じく、docker compose で起動した一式に向けて流す。
 *
 *   pnpm --filter e2e capture          # docs/images/ に PNG を書き、capture/output/ に動画を残す
 *   tests/e2e/capture/make-gifs.sh     # 動画を GIF にして docs/images/ に置く
 *
 * **E2E の CI では流さない**（設定を分けている）。画面を変えたら、手で撮り直す。
 * ログインは E2E と同じく、setup が画面から 1 回だけ行って Cookie を保存する。撮る側は保存した Cookie だけを使う。
 * パスワードを入力する setup では何も記録しない。撮る側の動画にも、パスワードの欄は映らない
 */
export default defineConfig({
  globalSetup: "./capture/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  // 動画を撮るぶん、ゆっくり進める
  timeout: 180_000,
  outputDir: "./capture/output/test-results",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    locale: "ja-JP",
    colorScheme: "light",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  projects: [
    { name: "setup", testDir: ".", testMatch: /auth\.setup\.ts$/ },
    { name: "capture", testDir: "./capture", testMatch: /\.capture\.ts$/, dependencies: ["setup"] },
  ],
});
