import { NextResponse, type NextRequest } from "next/server";
import { refreshTokens } from "@/lib/auth/oidc";
import { appOrigin, isSameOriginRequest, isSecure } from "@/lib/auth/request";
import { readRefreshToken, readSession, writeSession } from "@/lib/auth/session";
import { contentSecurityPolicy, createNonce, originsFrom } from "@/lib/content-security-policy";

/** 期限の少し前に更新する。ちょうど切れるころに送ると、バックエンドに届いた時点で切れている */
const REFRESH_MARGIN_SECONDS = 60;

/**
 * 画面と API の入口。
 *
 * - 状態を変える要求 … ほかのオリジンから来たものは 403 で止める（{@link rejectCrossOrigin}）
 * - `/api` … バックエンドへ中継し、利用者のアクセストークンを付ける（{@link proxyApi}）
 * - ログインが要る画面 … ログインしていなければ、開こうとした画面を戻り先にしてログインへ移す（{@link requireLogin}）
 * - 画面 … 要求ごとの nonce で CSP を付ける（{@link withContentSecurityPolicy}）
 */
export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (!isSameOriginRequest(originOf(request))) return rejectCrossOrigin(request);
  if (pathname.startsWith("/api/")) return proxyApi(request);
  if (LOGIN_REQUIRED.test(pathname) && !(await readSession(request.cookies))) return requireLogin(request);
  return withContentSecurityPolicy(request);
}

/** ログインが要る画面。/login と /auth は含めない（含めると、ログインへ移す先でまたログインを求める） */
const LOGIN_REQUIRED = /^\/(t|invitations)(\/|$)/;

function originOf(request: NextRequest) {
  return {
    method: request.method,
    origin: request.headers.get("origin"),
    secFetchSite: request.headers.get("sec-fetch-site"),
    appOrigin: appOrigin(request),
  };
}

/**
 * ほかのオリジンから来た、状態を変える要求を止める（DEV-124）。`/api` にはバックエンドと同じ形（RFC 9457）で返す。
 *
 * ログには方法と送り元だけを残す。パスには招待のトークンが入りうる
 */
function rejectCrossOrigin(request: NextRequest) {
  console.warn(
    "送り元の違う要求を止めました",
    JSON.stringify({ method: request.method, origin: request.headers.get("origin") }),
  );
  if (!request.nextUrl.pathname.startsWith("/api/")) return new NextResponse(null, { status: 403 });
  return NextResponse.json(
    { type: "about:blank", title: "権限がありません", status: 403, detail: "この画面の外からの要求は受け付けません" },
    { status: 403, headers: { "content-type": "application/problem+json" } },
  );
}

const CSP_HEADER = "Content-Security-Policy-Report-Only";

/**
 * 画面に CSP（いまは Report-Only）を付ける（`lib/content-security-policy.ts`、DEV-123）。
 *
 * 同じ値を要求のヘッダにも載せる。Next.js は要求の CSP から nonce を読み、自分が出すスクリプトに付ける
 */
function withContentSecurityPolicy(request: NextRequest) {
  const policy = contentSecurityPolicy({
    nonce: createNonce(),
    development: process.env.NODE_ENV === "development",
    imageOrigins: originsFrom(process.env.CSP_IMG_SRC),
    connectOrigins: originsFrom(process.env.CSP_CONNECT_SRC),
  });
  const headers = new Headers(request.headers);
  headers.set(CSP_HEADER, policy);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set(CSP_HEADER, policy);
  return response;
}

/**
 * 開こうとした画面（パスとクエリ）を戻り先にしてログインへ移す。
 *
 * レイアウトでも同じ判定をしているが、レイアウトは開いているパスを知らない。
 * そこで戻り先を決めると、テナントのトップ（`/t/{slug}`）にしか戻せず、共有されたリンクの画面に着かない。
 * 認可ではない。セッションがあるかどうかだけを見て、中身の判定はバックエンドに任せる
 */
function requireLogin(request: NextRequest) {
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
  // 画面はすべて（CSP を付けるため）。ログインを求めるのは LOGIN_REQUIRED のものだけ。
  // 静的なファイルと、CSP の違反の報告（/csp-report）は通さない
  matcher: ["/((?!_next/static|_next/image|favicon.ico|csp-report).*)"],
};
