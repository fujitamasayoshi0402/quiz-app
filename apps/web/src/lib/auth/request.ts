import type { NextRequest } from "next/server";

/**
 * 利用者から見たこのアプリのオリジン。認証基盤に渡すコールバックとログアウトの戻り先に使う。
 *
 * Amplify では、SSR はリクエストの転送先で動く。`request.url` のホストが利用者の見ているものと違いうるため、転送のヘッダを優先する。
 * 認証基盤は、登録したオリジン（dev.<ドメイン>、localhost）以外への戻りを拒む。
 */
export function appOrigin(request: NextRequest): string {
  const host = firstOf(request.headers.get("x-forwarded-host")) ?? request.headers.get("host") ?? request.nextUrl.host;
  const protocol = firstOf(request.headers.get("x-forwarded-proto")) ?? request.nextUrl.protocol.replace(":", "");
  return `${protocol}://${host}`;
}

/** http（localhost）では Cookie に Secure を付けない */
export function isSecure(origin: string): boolean {
  return origin.startsWith("https://");
}

/**
 * ログインのあとの戻り先。**このアプリの中のパスだけ**を許す。
 * `//evil.example` のようなものを通すと、ログインを経由した外部への誘導（オープンリダイレクト）になる
 */
export function safeReturnTo(value: string | null | undefined): string {
  return value && value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/\\") ? value : "/";
}

function firstOf(value: string | null): string | undefined {
  return value?.split(",")[0]?.trim() || undefined;
}
