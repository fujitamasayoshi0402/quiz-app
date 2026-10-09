import { NextResponse, type NextRequest } from "next/server";
import { SCOPES, clientId, providerMetadata } from "@/lib/auth/oidc";
import { appOrigin, isSecure, safeReturnTo } from "@/lib/auth/request";
import { writeLoginState } from "@/lib/auth/session";

/**
 * ログインを始める。認証基盤のログインの画面（Cognito の Managed Login）へ移る。
 *
 * - `state` … 戻ってきた要求が、このブラウザで始めたログインのものかを確かめる（CSRF）
 * - PKCE … 認可コードが途中で盗まれても、verifier を持たない者はトークンに換えられない
 *
 * どちらも暗号化した Cookie に置き、コールバックで照合する。
 */
export async function GET(request: NextRequest) {
  const origin = appOrigin(request);
  const state = randomToken();
  const codeVerifier = randomToken();

  const authorize = new URL((await providerMetadata()).authorization_endpoint);
  authorize.search = new URLSearchParams({
    response_type: "code",
    client_id: clientId(),
    redirect_uri: `${origin}/auth/callback`,
    scope: SCOPES,
    state,
    code_challenge: await challengeOf(codeVerifier),
    code_challenge_method: "S256",
    lang: "ja",
  }).toString();

  const response = NextResponse.redirect(authorize);
  const returnTo = safeReturnTo(request.nextUrl.searchParams.get("returnTo"));
  await writeLoginState(response.cookies, { state, codeVerifier, returnTo }, isSecure(origin));
  return response;
}

function randomToken(): string {
  return Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString("base64url");
}

async function challengeOf(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return Buffer.from(digest).toString("base64url");
}
