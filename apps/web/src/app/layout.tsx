import type { Metadata } from "next";
import { Geist } from "next/font/google";
import { currentUser } from "@/lib/auth/session";
import { cn } from "@/lib/utils";
import { Providers } from "./providers";
import "./globals.css";

const geist = Geist({ subsets: ["latin"], variable: "--font-sans" });

export const metadata: Metadata = {
  title: "Quiz",
  description: "カテゴリと難易度を自由に定義できるクイズアプリ",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const user = await currentUser();
  return (
    <html lang="ja" className={cn("font-sans", geist.variable)}>
      <body className="bg-muted/40 min-h-svh antialiased">
        {/* 利用者が替わったら、キャッシュごと作り直す。前の利用者の応答が画面に残らないようにする */}
        <Providers key={user?.id ?? "anonymous"}>{children}</Providers>
      </body>
    </html>
  );
}
