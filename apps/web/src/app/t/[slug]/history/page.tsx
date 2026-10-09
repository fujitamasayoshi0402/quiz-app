import { AttemptHistory } from "@/components/history/attempt-history";
import { CategoryScores } from "@/components/history/category-scores";

export default async function HistoryPage({ params }: PageProps<"/t/[slug]/history">) {
  const { slug } = await params;
  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">履歴</h1>
      <section className="space-y-3">
        <h2 className="font-semibold">カテゴリ別の正答率</h2>
        <CategoryScores slug={slug} />
      </section>
      <section className="space-y-3">
        <h2 className="font-semibold">解いたクイズ</h2>
        <AttemptHistory slug={slug} />
      </section>
    </div>
  );
}
