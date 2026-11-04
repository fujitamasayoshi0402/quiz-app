/**
 * ログインとログアウトの戻り先（`quizapp://auth/callback`、`quizapp://auth/logout`）か。Expo Go の `exp://…/--/auth/callback` も含む。
 *
 * Android では、戻り先がアプリへのリンクとしても届き、Expo Router が同じ名前の画面を探して「Unmatched Route」を出す（DEV-210）。
 * コードを受けるのは `openAuthSessionAsync` の側なので、画面の行き先としては捨てる（`app/+native-intent.ts`）
 */
export function isAuthRedirect(path: string): boolean {
  return /(?:^|\/)auth\/(?:callback|logout)(?:[?#]|$)/.test(path);
}
