import { NextResponse, type NextRequest } from "next/server";
import { demoCredentials, demoTokens } from "@/lib/auth/demo";
import { identityOf, revoke } from "@/lib/auth/oidc";
import { appOrigin, isSecure } from "@/lib/auth/request";
import { LOGIN_COOKIE, isSharedSession, readRefreshToken, writeSession } from "@/lib/auth/session";

/**
 * 共有のデモのアカウントで入る（ADR-0029）。ログインの画面の「デモとして入る」から送る。
 *
 * - POST だけを受ける。ほかのオリジンからの要求は、web の proxy が止める（`isSameOriginRequest`）。
 *   ほかのサイトから、見た人を勝手にデモのアカウントでログインさせない
 * - 入ったら `/` へ移す。デモのアカウントの所属は 1 つなので、そのテナントへ自動で移る。slug をここに書かない
 * - ログインしている人が押したら、いまのセッションを捨てて入り直す。共有でないセッションなら、ログアウトと同じく失効させる
 * - 失敗したら、ログインの画面に戻す。Cognito のエラーの中身は画面に出さない
 */
export async function POST(request: NextRequest) {
  if (!demoCredentials()) return new NextResponse(null, { status: 404 });
  const origin = appOrigin(request);

  try {
    const tokens = await demoTokens();
    const identity = identityOf(tokens.idToken);
    if (!identity) return failed(origin);

    const previous = await readRefreshToken(request.cookies);
    if (previous && !(await isSharedSession(request.cookies))) await revoke(previous);

    const response = NextResponse.redirect(new URL("/", origin), 303);
    await writeSession(response.cookies, tokens, identity, isSecure(origin), { shared: true });
    response.cookies.delete(LOGIN_COOKIE);
    return response;
  } catch (error) {
    console.error("デモのアカウントで入れませんでした", error);
    return failed(origin);
  }
}

function failed(origin: string) {
  return NextResponse.redirect(new URL("/login?error=demo", origin), 303);
}
