import { expect, test } from "@playwright/test";
import { signIn } from "../support/sign-in";
import { tenant, users } from "../support/users";

/**
 * ログインとログアウト。保存した Cookie は使わず、画面からログインする。
 * このファイルはパスワードを入力するので、トレースもスクリーンショットも残さない（playwright.config.ts）
 */
test("未ログインで開いた画面に、ログインのあとで戻る。ログアウトすると入れなくなる", async ({ page }) => {
  await page.goto(`/t/${tenant.slug}/admin/quizzes`);
  await expect(page).toHaveURL(/\/login\?returnTo=/);

  await signIn(page, users.admin);
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
  await expect(page.getByRole("banner").getByText(users.admin)).toBeVisible();

  await page.getByRole("button", { name: "ログアウト" }).click();
  await expect(page).toHaveURL(/\/login$/);

  await page.goto(`/t/${tenant.slug}/play`);
  await expect(page).toHaveURL(/\/login\?returnTo=/);
});

test("招待のリンクを未ログインで開くと、招待されたアドレスで入るよう案内される", async ({ page }) => {
  await page.goto("/invitations/not-a-real-token");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Finvitations%2F/);
  await expect(page.getByText("招待されたメールアドレスで、ログインまたはアカウントの作成をしてください")).toBeVisible();
});
