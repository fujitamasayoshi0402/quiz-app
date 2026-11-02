import { isAuthRedirect } from "@/lib/auth/redirect";

/**
 * OS から届いたリンクを、開く画面に写す。ログインとログアウトの戻り先は、画面を開かない（DEV-210）。
 * 起動のときに届いたら最初の画面を、動いているときに届いたらいまの画面のままにする
 */
export function redirectSystemPath({ path, initial }: { path: string; initial: boolean }): string | null {
  if (isAuthRedirect(path)) return initial ? "/" : null;
  return path;
}
