import { redirect } from "next/navigation";
import { PlayResult } from "@/components/play/play-result";
import { parseMode } from "@/lib/play/mode";

export default async function ResultPage({ params, searchParams }: PageProps<"/t/[slug]/play/result">) {
  const { slug } = await params;
  const { attempt, mode } = await searchParams;
  if (typeof attempt !== "string") redirect(`/t/${slug}/play`);
  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold tracking-tight">結果</h1>
      <PlayResult slug={slug} attemptId={attempt} mode={parseMode(mode)} />
    </div>
  );
}
