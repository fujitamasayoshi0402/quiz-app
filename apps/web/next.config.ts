import type { NextConfig } from "next";

/** バックエンド（quiz-service）の場所。ローカルでは bootRun で 8080 に立つ */
const apiOrigin = process.env.API_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  /**
   * `/api` をバックエンドへ中継する。ブラウザからは同一オリジンに見えるため、CORS の設定が要らない。
   * 利用者の識別ヘッダは中継の手前で proxy が付ける（src/proxy.ts）。
   */
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiOrigin}/api/:path*` }];
  },
};

export default nextConfig;
