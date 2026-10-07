import { expect, type Page } from "@playwright/test";
import { password } from "./users";

/**
 * アプリのログインの画面（`/login`）から、Cognito の Managed Login でログインし、アプリに戻るまで待つ。
 *
 * **Cognito の画面に依存するのはここだけ。** 文言は日本語の画面（`lang=ja`）のもの。
 * 認証を自前の実装に替えるなら（ADR-0027）、ここを Chrome の仮想の認証器でのログインに書き換える
 */
export async function signIn(page: Page, email: string) {
  const appOrigin = new URL(page.url()).origin;
  await page.getByRole("link", { name: "ログイン / アカウントを作る" }).click();

  const emailBox = page.getByRole("textbox", { name: "E メールアドレス" });
  const passwordBox = page.getByRole("textbox", { name: "パスワード" });

  // Cognito の画面は、読み込みの途中で入力欄を描き直すことがあり、先に入れたアドレスが消える。
  // パスワードの欄が出るまで、入れ直して進める
  await expect(async () => {
    if (await passwordBox.isVisible()) return;
    await emailBox.fill(email, { timeout: 2_000 });
    await page.getByRole("button", { name: "次へ" }).click({ timeout: 2_000 });
    await expect(passwordBox).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 30_000 });

  // 入れた値を確かめる検証（toHaveValue）は使わない。失敗したときのメッセージにパスワードが出る
  try {
    await passwordBox.fill(password());
    await page.getByRole("button", { name: "続行" }).click();

    // コールバック（/auth/callback）を経て、ログインの前に開こうとした画面に戻る。
    // テストの制限時間より短く待つ。制限時間で打ち切られると、下の後始末が動かない
    await page.waitForURL((url) => url.origin === appOrigin && !url.pathname.startsWith("/auth/"), {
      timeout: 15_000,
    });
  } catch (error) {
    // 失敗したときに Playwright が残す画面の記録（アクセシビリティのツリー）には、入力欄の値がそのまま入る。
    // パスワードの欄が残っていれば、空にしてから失敗させる
    await passwordBox.fill("", { timeout: 1_000 }).catch(() => {});
    throw error;
  }
}
