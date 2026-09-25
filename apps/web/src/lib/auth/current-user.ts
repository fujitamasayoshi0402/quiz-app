import { cookies } from "next/headers";
import type { Identity } from "@/lib/auth/oidc";
import { readSession } from "@/lib/auth/session";

/**
 * ログインしている利用者。セッションが無いか、読めなければ undefined。
 *
 * **画面の出し分けにだけ使う。** アクセストークンの期限は見ない（切れていれば proxy が更新する）。
 * 認可はバックエンドが、トークンと所属で判定する。
 */
export async function currentUser(): Promise<Identity | undefined> {
  const session = await readSession(await cookies());
  return session && { sub: session.sub, email: session.email };
}
