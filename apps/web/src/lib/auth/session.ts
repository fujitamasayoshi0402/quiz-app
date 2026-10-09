import { EncryptJWT, jwtDecrypt, type JWTPayload } from "jose";
import type { Identity, TokenSet } from "@/lib/auth/oidc";

/**
 * ログインのセッション。トークンを暗号化して `HttpOnly` の Cookie に置く（ADR-0016）。
 *
 * **ブラウザの JavaScript からは読めない。** API に付けるのは web の proxy（`src/proxy.ts`）だけ。
 *
 * Cookie は 2 つに分ける。Cognito のアクセストークンとリフレッシュトークンを 1 つに入れると、
 * 暗号化して 4 KB（Cookie の大きさの上限）を超えうる。
 */

/** アクセストークンと、画面に出す利用者の情報 */
export const SESSION_COOKIE = "qa_session";
/** リフレッシュトークン。アクセストークンの期限が切れたら、proxy がこれで更新する */
export const REFRESH_COOKIE = "qa_refresh";
/** ログインの途中の状態（state、PKCE の verifier、戻り先）。認証基盤から戻るまでの間だけ使う */
export const LOGIN_COOKIE = "qa_login";

/** リフレッシュトークンの期限（Cognito の web のクライアントの設定、30 日）に合わせる */
const SESSION_MAX_AGE = 30 * 24 * 60 * 60;
const LOGIN_MAX_AGE = 10 * 60;

export type Session = Identity & { accessToken: string; expiresAt: number };
export type LoginState = { state: string; codeVerifier: string; returnTo: string };

type CookieOptions = { httpOnly: true; secure: boolean; sameSite: "lax"; path: "/"; maxAge: number };
type CookieWriter = { set(name: string, value: string, options: CookieOptions): unknown };
type CookieReader = { get(name: string): { value: string } | undefined };

let key: Promise<Uint8Array> | undefined;

/** 暗号鍵は、環境変数の秘密から SHA-256 で 32 バイトに揃える（A256GCM） */
function encryptionKey(): Promise<Uint8Array> {
  key ??= (async () => {
    const secret = process.env.AUTH_SESSION_SECRET;
    if (!secret) throw new Error("AUTH_SESSION_SECRET が設定されていません");
    return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret)));
  })();
  return key;
}

async function seal(payload: JWTPayload, maxAge: number): Promise<string> {
  return new EncryptJWT(payload)
    .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
    .setIssuedAt()
    .setExpirationTime(`${maxAge}s`)
    .encrypt(await encryptionKey());
}

/** 読めない（改ざん、鍵の入れ替え、期限切れ）ものは、無いものとして扱う */
async function open<T>(value: string | undefined): Promise<T | undefined> {
  if (!value) return undefined;
  try {
    const { payload } = await jwtDecrypt(value, await encryptionKey());
    return payload as T;
  } catch {
    return undefined;
  }
}

/** localhost（http）では Secure を外す。付けるとブラウザが Cookie を保存しない */
export function cookieOptions(secure: boolean, maxAge = SESSION_MAX_AGE): CookieOptions {
  return { httpOnly: true, secure, sameSite: "lax", path: "/", maxAge };
}

export async function readSession(cookies: CookieReader): Promise<Session | undefined> {
  return open<Session>(cookies.get(SESSION_COOKIE)?.value);
}

export async function readRefreshToken(cookies: CookieReader): Promise<string | undefined> {
  return (await open<{ refreshToken: string }>(cookies.get(REFRESH_COOKIE)?.value))?.refreshToken;
}

/**
 * トークンを Cookie に書く。更新（リフレッシュ）のときは、リフレッシュトークンと利用者の情報を前のものから引き継ぐ。
 */
export async function writeSession(
  cookies: CookieWriter,
  tokens: TokenSet,
  identity: Identity,
  secure: boolean,
): Promise<void> {
  const session: Session = { ...identity, accessToken: tokens.accessToken, expiresAt: tokens.expiresAt };
  cookies.set(SESSION_COOKIE, await seal(session, SESSION_MAX_AGE), cookieOptions(secure));
  if (tokens.refreshToken) {
    const refresh = await seal({ refreshToken: tokens.refreshToken }, SESSION_MAX_AGE);
    cookies.set(REFRESH_COOKIE, refresh, cookieOptions(secure));
  }
}

export async function writeLoginState(cookies: CookieWriter, state: LoginState, secure: boolean): Promise<void> {
  cookies.set(LOGIN_COOKIE, await seal(state, LOGIN_MAX_AGE), cookieOptions(secure, LOGIN_MAX_AGE));
}

export async function readLoginState(cookies: CookieReader): Promise<LoginState | undefined> {
  return open<LoginState>(cookies.get(LOGIN_COOKIE)?.value);
}
