/**
 * ログインの状態。端末の安全な置き場所（`session-store.ts`）に置く（ADR-0032）。
 *
 * ID トークンは持たない。API に使うのはアクセストークンだけで、利用者の情報はバックエンドが返す
 */
export type Session = {
  accessToken: string;
  refreshToken: string;
  /** アクセストークンの期限（エポック秒） */
  expiresAt: number;
};

/** 期限まで、これより短ければ、API を呼ぶ前に更新する。呼んでいる間に切れないようにする */
const REFRESH_MARGIN_SECONDS = 5 * 60;

export function needsRefresh(session: Session, nowSeconds: number): boolean {
  return session.expiresAt - nowSeconds < REFRESH_MARGIN_SECONDS;
}

type TokenResponseLike = {
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
  issuedAt: number;
};

/**
 * トークンの応答から、ログインの状態を作る。
 *
 * Cognito は、リフレッシュトークンで更新したときに新しいリフレッシュトークンを返さない。そのときは前のものを使い続ける。
 * 期限が書かれていなければ、すぐに更新する扱いにする
 */
export function sessionFrom(response: TokenResponseLike, previous?: Session): Session | null {
  const refreshToken = response.refreshToken ?? previous?.refreshToken;
  if (!refreshToken) return null;
  return {
    accessToken: response.accessToken,
    refreshToken,
    expiresAt: response.issuedAt + (response.expiresIn ?? 0),
  };
}

/** 端末から読んだ値を確かめる。形が違えば（前の版のアプリが書いたなど）、ログインしていない扱いにする */
export function parseSession(raw: string | null): Session | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (
      typeof value === "object" &&
      value !== null &&
      typeof (value as Session).accessToken === "string" &&
      typeof (value as Session).refreshToken === "string" &&
      typeof (value as Session).expiresAt === "number"
    ) {
      return value as Session;
    }
  } catch {
    // 壊れた値はログインしていない扱いにする
  }
  return null;
}
