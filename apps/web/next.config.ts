import path from "node:path";
import type { NextConfig } from "next";

/**
 * すべての応答に付けるセキュリティのヘッダ（DEV-123）。
 *
 * CSP のうち、止めても画面が壊れない指示だけをここで強制する（ほかのサイトへの埋め込み、`<base>`、`<object>` の禁止）。
 * スクリプトや画像の送り先を絞る本体は、要求ごとに nonce が要るので proxy が付ける（`lib/content-security-policy.ts`）
 */
const securityHeaders = [
  // https で来たものにだけ効く。localhost（http）ではブラウザが読まない
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // frame-ancestors を読まない古いブラウザのため
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'; base-uri 'self'; object-src 'none'" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

const nextConfig: NextConfig = {
  /**
   * コンテナ用に、実行に必要なファイルだけを `.next/standalone` へまとめる。
   * monorepo なので、依存の追跡はリポジトリのルートから行う（pnpm の node_modules がルートにある）。
   */
  output: "standalone",
  outputFileTracingRoot: path.join(import.meta.dirname, "../.."),
  // 使っているフレームワークを応答で名乗らない
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
