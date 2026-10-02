/**
 * 画面の Content-Security-Policy（DEV-123）。proxy が要求ごとに nonce を作って組み立てる。
 *
 * **いまは Report-Only で出す。** 違反しても止めず、`/csp-report` に届けてログに残す。
 * 解説図の CDN や S3 への直接のアップロードなど、環境ごとに違う送り先を取りこぼしていないかを確かめてから、強制に切り替える。
 * 止めても画面が壊れない指示（埋め込みの禁止など）は、`next.config.ts` が別の CSP として強制で付けている。
 *
 * - スクリプトは nonce を付けたものだけ。Next.js は要求の CSP から nonce を読み、自分のスクリプトに付ける。
 *   `strict-dynamic` で、それらが読み込むスクリプトも許す
 * - スタイルは inline を許す。Next.js と UI の部品が style 属性を使う。スタイルからスクリプトは動かない
 * - 画像は、解説図の CDN（署名付き URL へ移る先）。接続は、S3 への直接のアップロード（ADR-0020）。どちらも環境ごとに違うので環境変数で受ける
 * - 埋め込むのは draw.io（`components/admin/figure-editor.tsx`）だけ
 */
export const CSP_REPORT_PATH = "/csp-report";

type Options = {
  nonce: string;
  /** 開発用のサーバー（`next dev`）か。eval と、更新を知らせる WebSocket を使う */
  development: boolean;
  imageOrigins: string[];
  connectOrigins: string[];
};

const DRAWIO_ORIGIN = "https://embed.diagrams.net";

export function contentSecurityPolicy({ nonce, development, imageOrigins, connectOrigins }: Options): string {
  const directives: Record<string, string[]> = {
    "default-src": ["'self'"],
    "script-src": ["'self'", `'nonce-${nonce}'`, "'strict-dynamic'", ...(development ? ["'unsafe-eval'"] : [])],
    "style-src": ["'self'", "'unsafe-inline'"],
    "img-src": ["'self'", "data:", "blob:", ...imageOrigins],
    "font-src": ["'self'"],
    "connect-src": ["'self'", ...connectOrigins, ...(development ? ["ws:"] : [])],
    "frame-src": [DRAWIO_ORIGIN],
    "object-src": ["'none'"],
    "base-uri": ["'self'"],
    "frame-ancestors": ["'none'"],
    "report-uri": [CSP_REPORT_PATH],
  };
  return Object.entries(directives)
    .map(([name, values]) => `${name} ${values.join(" ")}`)
    .join("; ");
}

/** 環境変数の、空白で区切ったオリジンの並び。空や未設定なら空の配列 */
export function originsFrom(value: string | undefined): string[] {
  return value?.split(/\s+/).filter(Boolean) ?? [];
}

/** 要求ごとの nonce。推測されないよう、暗号用の乱数から作る */
export function createNonce(): string {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
}
