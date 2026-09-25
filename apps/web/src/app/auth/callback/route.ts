import { NextResponse, type NextRequest } from "next/server";
import { exchangeCode, identityOf } from "@/lib/auth/oidc";
import { appOrigin, isSecure } from "@/lib/auth/request";
import { LOGIN_COOKIE, readLoginState, writeSession } from "@/lib/auth/session";

/**
 * 認証基盤から戻ってくる。認可コードをトークンに換え、暗号化した Cookie に置く。
 *
 * 失敗したときはログインの画面に戻し、理由は出さない。
 * 認証基盤が返したエラーの内容を、そのまま画面に出すことはしない。
 */
export async function GET(request: NextRequest) {
  const origin = appOrigin(request);
  const params = request.nextUrl.searchParams;
  const login = await readLoginState(request.cookies);

  const code = params.get("code");
  if (!login || !code || params.get("state") !== login.state) {
    return failed(origin);
  }

  try {
    const tokens = await exchangeCode(code, `${origin}/auth/callback`, login.codeVerifier);
    const identity = identityOf(tokens.idToken);
    if (!identity) return failed(origin);

    const response = NextResponse.redirect(new URL(login.returnTo, origin));
    await writeSession(response.cookies, tokens, identity, isSecure(origin));
    response.cookies.delete(LOGIN_COOKIE);
    return response;
  } catch (error) {
    console.error("ログインを完了できませんでした", error);
    return failed(origin);
  }
}

function failed(origin: string) {
  const response = NextResponse.redirect(new URL("/login?error=1", origin));
  response.cookies.delete(LOGIN_COOKIE);
  return response;
}
