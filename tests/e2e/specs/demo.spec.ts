import { expect, test } from "@playwright/test";

/**
 * ログインせずに、共有のデモのアカウントで入る（DEV-151、ADR-0029）。
 *
 * 保存した Cookie は使わない。パスワードは画面では入力せず、web のサーバーが持つ（AUTH_DEMO_USER_*）。
 * デモのテナントとクイズはシード（`R__demo_data.sql`）が入れる。解きかけたクイズは `fixtures.sql` が消す
 */
test.use({ storageState: { cookies: [], origins: [] } });

const DEMO_EMAIL = "demo@example.com";

test("「デモとして入る」でデモのテナントに入り、クイズを解いて、ログアウトする", async ({ page }) => {
  await test.step("トップを開くとログインの画面になり、デモの入口がある", async () => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText("回答の履歴とランキングは、見た人どうしで共有されます", { exact: false })).toBeVisible();
  });

  await test.step("押すと、デモのテナントの出題の画面に入る", async () => {
    await page.getByRole("button", { name: "デモとして入る" }).click();
    await expect(page).toHaveURL(/\/t\/demo\/play$/);
    await expect(page.getByRole("banner").getByText(DEMO_EMAIL)).toBeVisible();
  });

  await test.step("クイズを始めて 1 問答える", async () => {
    await page.getByText("認証認可", { exact: true }).click();
    await page.getByRole("button", { name: "始める" }).click();
    await page.getByRole("listitem").first().getByRole("button").click();
    await page.getByRole("button", { name: "回答する" }).click();
    await expect(page.getByText(/^(正解|不正解)$/)).toBeVisible();
  });

  await test.step("ログアウトすると、ログインの画面に戻る", async () => {
    await page.getByRole("button", { name: "ログアウト" }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto("/t/demo/play");
    await expect(page).toHaveURL(/\/login\?returnTo=/);
  });
});

test("招待のリンクから来たときは、デモの入口を出さない", async ({ page }) => {
  await page.goto("/invitations/not-a-real-token");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Finvitations%2F/);
  await expect(page.getByRole("button", { name: "デモとして入る" })).toHaveCount(0);
});
