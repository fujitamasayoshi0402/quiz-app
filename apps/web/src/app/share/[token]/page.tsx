import type { Metadata } from "next";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Eyebrow, Panel, Stat } from "@/components/play/parts";
import { openShareCard, rateOf, textOf } from "@/lib/share/token";

/**
 * 共有した結果（DEV-207）。**ログインなしで開ける。** 中身はリンクのトークンだけが持つ（`lib/share/token.ts`）。
 *
 * カードの画像は、同じディレクトリの `opengraph-image.tsx` を Next.js が拾う。検索には載せない（`noindex`）
 */
export async function generateMetadata({ params }: PageProps<"/share/[token]">): Promise<Metadata> {
  const { token } = await params;
  const card = await openShareCard(token);
  if (!card) return { title: "Quiz", robots: { index: false } };
  const { title, description } = textOf(card);
  return {
    title: `${title} — Quiz`,
    description,
    robots: { index: false },
    openGraph: { type: "website", siteName: "Quiz", title, description, url: `/share/${token}`, locale: "ja_JP" },
    twitter: { card: "summary_large_image", title, description },
  };
}

export default async function SharePage({ params }: PageProps<"/share/[token]">) {
  const card = await openShareCard((await params).token);
  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      {card ? (
        <Panel className="space-y-6">
          <div className="space-y-2">
            <Eyebrow>QUIZ RESULT</Eyebrow>
            <h1 className="text-2xl font-semibold tracking-tight text-pretty">{card.label}</h1>
          </div>
          <dl className="grid grid-cols-2 gap-3">
            <Stat label="正答率" value={`${rateOf(card)}%`} />
            <Stat label="正解" value={`${card.correct} / ${card.total} 問`} />
          </dl>
          <p className="text-sm text-muted-foreground">
            Quiz は、カテゴリと難易度を自由に決めて、図解つきの 4 択のクイズを作って解けるアプリです。
          </p>
        </Panel>
      ) : (
        <Panel>
          <p className="font-semibold">このリンクは使えません</p>
        </Panel>
      )}
      <Button asChild size="lg" className="rounded-full">
        <Link href="/">このアプリで解いてみる</Link>
      </Button>
    </main>
  );
}
