/**
 * Phase 1 のスタブ認証で選べる利用者。dev プロファイルのシードデータと同じ識別子。
 *
 * ロールはここでは決まらない。バックエンドがテナントの所属（core.tenant_members）から引く。
 * Phase 3 で Cognito に替えるときに、このファイルとログイン画面を削除する。
 */
export const STUB_USERS = [
  { id: "957d085e-3b87-5fa7-9283-5eb6229216b1", name: "一般ユーザー", description: "クイズに回答する" },
  { id: "67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab", name: "管理者", description: "クイズの管理もできる" },
] as const;

/** 選んだ利用者を保持する Cookie。httpOnly にし、ブラウザのコードからは読ませない */
export const USER_COOKIE = "quiz-user";

/** テナント選択（別課題）ができるまでは、シードのテナントへ直接進む */
export const DEFAULT_TENANT = "demo";

export function findStubUser(id: string | undefined) {
  return STUB_USERS.find((user) => user.id === id);
}
