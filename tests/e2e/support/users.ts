import path from "node:path";

/**
 * E2E テストの利用者。Cognito の利用者は Terraform（`modules/auth` の `e2e_user_emails`）が作り、
 * テナントの所属とロールは `global-setup.ts` がローカルの DB に作る。パスワードは全員で共通。
 */
export const users = {
  admin: "e2e-admin@example.com",
  member: "e2e-member@example.com",
  outsider: "e2e-outsider@example.com",
  invitee: "e2e-invitee@example.com",
} as const;

export type Role = keyof typeof users;

export const roles = Object.keys(users) as Role[];

/** 管理者と一般ユーザーが所属するテナント。`fixtures.sql` が作る */
export const tenant = { slug: "e2e", name: "E2E テスト" } as const;

/** ログインしたあとの Cookie の置き場所（setup が書き、各テストが読む）。コミットしない */
export const storageStateOf = (role: Role) => path.join(__dirname, "..", ".auth", `${role}.json`);

export function password(): string {
  const value = process.env.E2E_USER_PASSWORD;
  if (!value) {
    throw new Error("E2E_USER_PASSWORD を渡してください（terraform output -raw e2e_user_password）");
  }
  return value;
}
