import type { Metadata } from "next";
import { Geist } from "next/font/google";
import { cn } from "@/lib/utils";
import { Providers } from "./providers";
import "./globals.css";

const geist = Geist({ subsets: ["latin"], variable: "--font-sans" });

export const metadata: Metadata = {
  title: "Quiz",
  description: "カテゴリと難易度を自由に定義できるクイズアプリ",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ja" className={cn("font-sans", geist.variable)}>
      <body className="bg-muted/40 min-h-svh antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
