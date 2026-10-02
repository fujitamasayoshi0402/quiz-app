import { defineConfig, devices } from "@playwright/test";

/** パスワードを入力するプロジェクトでは、何も記録しない */
const noRecording = { trace: "off", screenshot: "off", video: "off" } as const;

/**
 * 権限まわり（DEV-77）と、出題・管理の流れ（DEV-111）の E2E テスト。docker compose で起動した一式（http://localhost:3000）に向けて流す。
 *
 * ログインは、利用者ごとに 1 回だけ Managed Login の画面で行い、Cookie を保存して各テストで使い回す（`auth.setup.ts`）。
 * **パスワードを入力するプロジェクト（setup と login）では、トレースもスクリーンショットも残さない。**
 * トレースにも、失敗したときの画面の記録にも入力した値が残り、CI の成果物は公開リポジトリでは誰でも取り出せる。
 * CI は e2e のプロジェクトの結果だけを保存する（ci.yml）。保存した Cookie はその実行の暗号鍵で守られ、あとから使えない
 *
 * テスト同士が状態を共有する（招待を受け入れると所属が増える）ため、並列にしない。やり直しもしない。
 * 途中まで進んだテストをやり直すと、前の試行が残した状態で失敗する。
 */
export default defineConfig({
  testDir: ".",
  globalSetup: "./global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  // HTML のレポートは作らない。すべてのプロジェクトの結果を 1 つにまとめるため、ログインの記録も入る
  reporter: "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    locale: "ja-JP",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts$/, use: noRecording },
    // ログインそのものを確かめる。保存した Cookie は使わず、画面からログインする
    { name: "login", testMatch: /login\.spec\.ts$/, use: { ...devices["Desktop Chrome"], ...noRecording } },
    {
      name: "e2e",
      testMatch: /\.spec\.ts$/,
      testIgnore: /login\.spec\.ts$/,
      dependencies: ["setup"],
      use: devices["Desktop Chrome"],
    },
  ],
});
