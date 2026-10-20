import type { Metadata } from "next";
import { Geist } from "next/font/google";
import { headers } from "next/headers";
import { currentUser } from "@/lib/auth/current-user";
import { originOf } from "@/lib/auth/request";
import { cn } from "@/lib/utils";
import { Providers } from "./providers";
import "./globals.css";

const geist = Geist({ subsets: ["latin"], variable: "--font-sans" });

const description =
  "カテゴリと難易度を自由に決めて、4 択のクイズを作って解けるアプリ。解説には図・画像・PDF を入れられます。アカウントを作らずにデモを試せます。";

/**
 * リンクを共有したときのカード（OGP、Twitter）と、アイコン（DEV-194）。
 *
 * 画像（`opengraph-image.png`、`twitter-image.png`、`icon.svg`、`apple-icon.png`）は、このディレクトリに置いたものを Next.js が拾う。
 * 作り方は `tests/e2e/capture/web-images.mjs`。
 *
 * すべての画面で同じものを出す。ログインが要る画面の URL を貼られても、取りに来た側はログインの画面へ移され、
 * テナントの名前などの中身は出ない。
 *
 * カードの画像は絶対 URL で書く必要がある。環境（dev、prod、localhost）ごとに違うため、要求のヘッダから決める（`originOf`）
 */
export async function generateMetadata(): Promise<Metadata> {
  return {
    metadataBase: new URL(originOf(await headers())),
    title: "Quiz",
    description,
    applicationName: "Quiz",
    openGraph: {
      type: "website",
      siteName: "Quiz",
      title: "Quiz — 図解つきのクイズを作って解く",
      description,
      url: "/",
      locale: "ja_JP",
    },
    twitter: {
      card: "summary_large_image",
      title: "Quiz — 図解つきのクイズを作って解く",
      description,
    },
  };
}

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const user = await currentUser();
  return (
    <html lang="ja" className={cn("font-sans", geist.variable)}>
      <body className="min-h-svh bg-muted/40 antialiased">
        {/* 利用者が替わったら、キャッシュごと作り直す。前の利用者の応答が画面に残らないようにする */}
        <Providers key={user?.sub ?? "anonymous"}>{children}</Providers>
      </body>
    </html>
  );
}
