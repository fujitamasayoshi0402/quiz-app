import { NextResponse, type NextRequest } from "next/server";
import { logoutUrl, revoke } from "@/lib/auth/oidc";
import { appOrigin } from "@/lib/auth/request";
import { LOGIN_COOKIE, REFRESH_COOKIE, SESSION_COOKIE, isSharedSession, readRefreshToken } from "@/lib/auth/session";

/**
 * ログアウト。Cookie を消し、リフレッシュトークンを失効させ、認証基盤のセッションも終える。
 * 共有のデモのアカウントのセッションでは、リフレッシュトークンを失効させない（ADR-0029。ほかの人も使っている）。
 *
 * POST だけを受ける。GET にすると、ほかのサイトに置いた画像のリンクなどでログアウトさせられる。
 * 303 で返し、戻り先を GET で開かせる。
 */
export async function POST(request: NextRequest) {
  const origin = appOrigin(request);

  const refreshToken = await readRefreshToken(request.cookies);
  if (refreshToken && !(await isSharedSession(request.cookies))) await revoke(refreshToken);

  const response = NextResponse.redirect(await logoutUrl(`${origin}/`), 303);
  for (const name of [SESSION_COOKIE, REFRESH_COOKIE, LOGIN_COOKIE]) {
    response.cookies.delete(name);
  }
  return response;
}
