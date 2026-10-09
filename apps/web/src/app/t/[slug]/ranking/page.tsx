import { RankingBoard } from "@/components/ranking/ranking-board";

export default async function RankingPage({ params }: PageProps<"/t/[slug]/ranking">) {
  const { slug } = await params;
  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">ランキング</h1>
      <RankingBoard slug={slug} />
    </div>
  );
}
