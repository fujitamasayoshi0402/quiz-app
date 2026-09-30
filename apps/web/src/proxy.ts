import { NextResponse, type NextRequest } from "next/server";
import { refreshTokens } from "@/lib/auth/oidc";
import { appOrigin, isSecure } from "@/lib/auth/request";
import { readRefreshToken, readSession, writeSession } from "@/lib/auth/session";

/** 期限の少し前に更新する。ちょうど切れるころに送ると、バックエンドに届いた時点で切れている */
const REFRESH_MARGIN_SECONDS = 60;

/**
 * 画面と API の入口。
 *
 * - `/api` … バックエンドへ中継し、利用者のアクセストークンを付ける（{@link proxyApi}）
 * - ログインが要る画面 … ログインしていなければ、開こうとした画面を戻り先にしてログインへ移す（{@link requireLogin}）
 */
export async function proxy(request: NextRequest) {
  return request.nextUrl.pathname.startsWith("/api/") ? proxyApi(request) : requireLogin(request);
}

/**
 * ログインしていなければ、開こうとした画面（パスとクエリ）を戻り先にしてログインへ移す。
 *
 * レイアウトでも同じ判定をしているが、レイアウトは開いているパスを知らない。
 * そこで戻り先を決めると、テナントのトップ（`/t/{slug}`）にしか戻せず、共有されたリンクの画面に着かない。
 * 認可ではない。セッションがあるかどうかだけを見て、中身の判定はバックエンドに任せる
 */
async function requireLogin(request: NextRequest) {
  if (await readSession(request.cookies)) return NextResponse.next();

  const login = new URL("/login", appOrigin(request));
  login.searchParams.set("returnTo", `${request.nextUrl.pathname}${request.nextUrl.search}`);
  return NextResponse.redirect(login);
}

/**
 * `/api` のリクエストをバックエンドへ中継し、利用者のアクセストークンを付ける（ADR-0016）。
 *
 * **トークンを付けるのはこの proxy だけ**にする。トークンは暗号化した `HttpOnly` の Cookie にあり、
 * 画面のコードは認証を意識せずに API を呼べる。期限が近ければ、ここでリフレッシュトークンを使って更新する。
 *
 * ログインのセッションが無い要求は、`Authorization` をそのまま渡す。
 * スモークテストのように、トークンを自分で取る呼び出し元のため。受け付けるかどうかはバックエンドが検証して決める。
 *
 * 中継先は**実行時の環境変数**から読む。`next.config.ts` の rewrites はビルド時に固定されるため、
 * 同じイメージを dev と本番で使い回せない。
 */
async function proxyApi(request: NextRequest) {
  const headers = new Headers(request.headers);
  // セッションの Cookie はバックエンドに要らない。トークンを余計な経路に流さない
  headers.delete("cookie");

  const session = await readSession(request.cookies);
  let refreshed: Awaited<ReturnType<typeof refreshTokens>> | undefined;
  if (session) {
    let accessToken = session.accessToken;
    if (session.expiresAt - REFRESH_MARGIN_SECONDS < Date.now() / 1000) {
      refreshed = await refresh(request);
      // 更新できなければ、期限切れのトークンのまま送る。バックエンドが 401 を返し、画面がログインへ誘導する
      accessToken = refreshed?.accessToken ?? accessToken;
    }
    headers.set("authorization", `Bearer ${accessToken}`);
  }

  const apiOrigin = process.env.API_ORIGIN ?? "http://localhost:8080";
  const target = new URL(`${request.nextUrl.pathname}${request.nextUrl.search}`, apiOrigin);
  const response = NextResponse.rewrite(target, { request: { headers } });

  if (session && refreshed) {
    await writeSession(
      response.cookies,
      refreshed,
      { sub: session.sub, email: session.email },
      isSecure(appOrigin(request)),
    );
  }
  return response;
}

async function refresh(request: NextRequest) {
  const refreshToken = await readRefreshToken(request.cookies);
  if (!refreshToken) return undefined;
  try {
    return await refreshTokens(refreshToken);
  } catch (error) {
    console.warn("アクセストークンを更新できませんでした", error);
    return undefined;
  }
}

export const config = {
  // 画面はログインが要るものだけ。/login と /auth は含めない（含めると、ログインへ移す先でまたログインを求める）
  matcher: ["/api/:path*", "/t/:path*", "/invitations/:path*"],
};
