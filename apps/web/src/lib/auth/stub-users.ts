/**
 * Phase 1 のスタブ認証で選べる利用者。dev プロファイルのシードデータと同じ識別子・表示名。
 *
 * 名前に役割を入れない。役割はテナントごとに違うため、ヘッダに「管理者」と出ると一般ユーザーのテナントで誤解を招く。
 *
 * ロールと所属はここでは決まらない。バックエンドがテナントの所属（core.tenant_members）から引く。
 * 所属の数が 0 / 1 / 2 以上の利用者をそろえ、`/` の出し分けをすべて試せるようにしている。
 * Phase 3 で Cognito に替えるときに、このファイルとログイン画面を削除する。
 */
export const STUB_USERS = [
  {
    id: "957d085e-3b87-5fa7-9283-5eb6229216b1",
    name: "デモ利用者",
    description: "デモに所属。ログインするとそのまま入る",
  },
  {
    id: "67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab",
    name: "デモ管理者",
    description: "デモの管理者で、地理の勉強会では一般ユーザー。テナントを選んで入る",
  },
  {
    id: "7918a5c2-30ee-56c8-b76c-57c6a79774e3",
    name: "デモ未所属",
    description: "どのテナントにも招待されていない",
  },
] as const;

export type StubUser = (typeof STUB_USERS)[number];

/** 選んだ利用者を保持する Cookie。httpOnly にし、ブラウザのコードからは読ませない */
export const USER_COOKIE = "quiz-user";

export function findStubUser(id: string | undefined): StubUser | undefined {
  return STUB_USERS.find((user) => user.id === id);
}
