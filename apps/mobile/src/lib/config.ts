/**
 * ビルドのときに渡す接続先（`.env.example`）。`EXPO_PUBLIC_` の値は、バンドルを作るときに埋め込まれる。
 *
 * 無ければ起動の時点で落とす。空のまま動くと、ログインの画面やすべての API が、原因の分からないエラーになる
 */
function required(name: string, value: string | undefined): string {
  if (!value) {
    throw new Error(`${name} が設定されていません。apps/mobile/.env を作ってください（.env.example）`);
  }
  return value.replace(/\/+$/, "");
}

// process.env.EXPO_PUBLIC_* は、名前をそのまま書いたときだけ置き換わる。変数を介して読まない
export const config = {
  apiOrigin: required("EXPO_PUBLIC_API_ORIGIN", process.env.EXPO_PUBLIC_API_ORIGIN),
  authIssuer: required("EXPO_PUBLIC_AUTH_ISSUER", process.env.EXPO_PUBLIC_AUTH_ISSUER),
  authClientId: required("EXPO_PUBLIC_AUTH_CLIENT_ID", process.env.EXPO_PUBLIC_AUTH_CLIENT_ID),
};
