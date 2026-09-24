import { NextResponse, type NextRequest } from "next/server";
import { USER_COOKIE } from "@/lib/auth/stub-users";

/**
 * `/api` のリクエストをバックエンドへ中継し、利用者の識別を付ける。
 *
 * **利用者を示すのはこの proxy だけ**にする。ブラウザが付けた `X-User-Id` は捨て、
 * Cookie から組み立て直す。画面のコードは認証を意識せずに API を呼べる。
 * Phase 3 では、ここを「セッションから Cognito のトークンを取り出して Authorization に付ける」に替える。
 *
 * 中継先は**実行時の環境変数**から読む。`next.config.ts` の rewrites はビルド時に固定されるため、
 * 同じイメージを dev と本番で使い回せない。
 */
export function proxy(request: NextRequest) {
  const headers = new Headers(request.headers);
  headers.delete("x-user-id");

  const userId = request.cookies.get(USER_COOKIE)?.value;
  if (userId) {
    headers.set("x-user-id", userId);
  }

  const apiOrigin = process.env.API_ORIGIN ?? "http://localhost:8080";
  const target = new URL(`${request.nextUrl.pathname}${request.nextUrl.search}`, apiOrigin);
  return NextResponse.rewrite(target, { request: { headers } });
}

export const config = {
  matcher: "/api/:path*",
};
