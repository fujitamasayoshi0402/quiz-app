import { test as setup } from "@playwright/test";
import { signIn } from "./support/sign-in";
import { roles, storageStateOf, users } from "./support/users";

/**
 * 利用者ごとに 1 回だけ画面からログインし、Cookie を保存する。各テストはこれを読み込んで、ログインした状態から始める。
 * Cognito の画面に依存するのはここ（と `support/sign-in.ts`）だけ。認証を自前の実装に替えるなら（ADR-0027）、ここを書き換える
 */
for (const role of roles) {
  setup(`${role} でログインする`, async ({ page }) => {
    await page.goto("/login");
    await signIn(page, users[role]);
    await page.context().storageState({ path: storageStateOf(role) });
  });
}
