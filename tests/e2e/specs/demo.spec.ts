import { expect, test } from "@playwright/test";

/**
 * ログインせずに、共有のデモのアカウントで入る（DEV-151、ADR-0029）。
 *
 * 保存した Cookie は使わない。パスワードは画面では入力せず、web のサーバーが持つ（AUTH_DEMO_USER_*）。
 * デモのテナントはシード（`R__demo_data.sql`）、クイズはデモのパック（`global-setup.ts`）が入れる。解きかけたクイズは `fixtures.sql` が消す
 */
test.use({ storageState: { cookies: [], origins: [] } });

const DEMO_EMAIL = "demo@example.com";

test("「デモとして入る」でデモのテナントに入り、クイズを解いて、ログアウトする", async ({ page }) => {
  await test.step("トップを開くと、アプリの説明とデモの入口がある", async () => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Quiz", level: 1 })).toBeVisible();
    await expect(page.getByText("回答の履歴とランキングは、見た人どうしで共有されます", { exact: false })).toBeVisible();
  });

  await test.step("押すと、デモのテナントの出題の画面に入る", async () => {
    await page.getByRole("button", { name: "デモとして入る" }).click();
    await expect(page).toHaveURL(/\/t\/demo\/play$/);
    await expect(page.getByRole("banner").getByText(DEMO_EMAIL)).toBeVisible();
  });

  await test.step("カテゴリごとに、代表の図と、問題数・所要時間の目安が出る（DEV-197）", async () => {
    const category = page.locator("label", { hasText: "世界の地理" });
    await expect(category.getByText(/\d+ 問・約 \d+ 分/)).toBeVisible();
    // 図は API から署名付き URL へ送られ、別のドメインから届く。読み込めたこと（CSP に止められていないこと）まで見る
    const cover = category.locator("img");
    await expect(cover).toBeVisible();
    await expect.poll(() => cover.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
  });

  await test.step("クイズを始めて 1 問答える", async () => {
    await page.getByText("世界の地理", { exact: true }).click();
    await page.getByRole("button", { name: "始める" }).click();
    await page.getByRole("listitem").first().getByRole("button").click();
    await page.getByRole("button", { name: "回答する" }).click();
    await expect(page.getByText(/^(正解|不正解)$/)).toBeVisible();
  });

  await test.step("ログアウトすると、トップに戻る", async () => {
    await page.getByRole("button", { name: "ログアウト" }).click();
    await expect(page.getByRole("button", { name: "デモとして入る" })).toBeVisible();
    await page.goto("/t/demo/play");
    await expect(page).toHaveURL(/\/login\?returnTo=/);
  });
});

test("デモのアカウントで管理画面を見学できる。保存しても変わらず、招待の一覧は出ない（DEV-196）", async ({ page }) => {
  await test.step("デモとして入り、ヘッダの「管理」から管理画面を開く", async () => {
    await page.goto("/");
    await page.getByRole("button", { name: "デモとして入る" }).click();
    await expect(page).toHaveURL(/\/t\/demo\/play$/);
    await page.getByRole("link", { name: "管理", exact: true }).click();
    await expect(page).toHaveURL(/\/t\/demo\/admin\/quizzes$/);
    await expect(page.getByText("見学中です")).toBeVisible();
  });

  await test.step("クイズを開いて保存すると、見学では変更できないと出る", async () => {
    await page.getByRole("table").getByRole("link").first().click();
    await page.getByRole("button", { name: "公開したまま保存" }).click();
    await expect(page.getByText("見学では変更できません")).toBeVisible();
  });

  await test.step("招待の画面では、一覧の代わりに理由を出す", async () => {
    await page.getByRole("link", { name: "招待", exact: true }).click();
    await expect(page.getByText("見学では出しません", { exact: false })).toBeVisible();
  });
});

test("スマホの幅で、カテゴリを選ぶと、その下の「範囲」まで送る（DEV-213）", async ({ page }) => {
  // デモはカテゴリが多く代表の図も付くため、スマホの幅では選んだカテゴリの下が画面の外に出る
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto("/");
  await page.getByRole("button", { name: "デモとして入る" }).click();
  await expect(page).toHaveURL(/\/t\/demo\/play$/);

  await page.getByText("世界の地理", { exact: true }).click();
  await expect(page.getByRole("heading", { name: "範囲" })).toBeInViewport();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
});

test("トップの「管理を試す」から、ログインを経てテナントの作成へ向かう", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "アカウントを作って始める" }).click();
  await expect(page).toHaveURL(/\/login\?returnTo=%2Ftenants%2Fnew$/);
  await expect(page.getByRole("link", { name: "ログイン / アカウントを作る" })).toHaveAttribute(
    "href",
    "/auth/login?returnTo=%2Ftenants%2Fnew",
  );
});

test("招待のリンクから来たときは、デモの入口を出さない", async ({ page }) => {
  await page.goto("/invitations/not-a-real-token");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Finvitations%2F/);
  await expect(page.getByRole("button", { name: "デモとして入る" })).toHaveCount(0);
});
