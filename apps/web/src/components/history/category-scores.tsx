"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useListCategoryScores } from "@/lib/api/generated/endpoints";
import type { CategoryScore } from "@/lib/api/generated/model";

/**
 * カテゴリ別の正答率。
 *
 * 数えるのは、いま出題できるクイズへの最新の回答だけ（バックエンドが決める）。
 * 解いていないカテゴリも並べる。どこに手を付けていないかも、次に解くものを選ぶ手がかりになる。
 */
export function CategoryScores({ slug }: { slug: string }) {
  const scores = useListCategoryScores(slug);

  if (scores.isPending) return <Skeleton className="h-40 w-full" />;
  if (scores.isError) return <ApiErrorAlert error={scores.error} />;
  if (scores.data.length === 0) {
    return <p className="text-muted-foreground text-sm">出題できるクイズがまだありません。</p>;
  }

  return (
    <ul className="divide-y rounded-lg border">
      {scores.data.map((score) => (
        <ScoreRow key={score.categoryId} score={score} />
      ))}
    </ul>
  );
}

function ScoreRow({ score }: { score: CategoryScore }) {
  const { name, quizCount, answeredCount, correctCount } = score;
  const rate = answeredCount === 0 ? null : Math.round((correctCount / answeredCount) * 100);

  return (
    <li className="space-y-2 p-4">
      <div className="flex items-baseline justify-between gap-4">
        <span className="min-w-0 font-medium break-words">{name}</span>
        <span className="shrink-0 text-lg font-semibold tabular-nums">{rate == null ? "—" : `${rate}%`}</span>
      </div>
      <Progress value={rate ?? 0} aria-label={`${name} の正答率`} />
      <p className="text-muted-foreground text-xs">
        {answeredCount === 0
          ? `${quizCount} 問。まだ解いていません`
          : `${quizCount} 問中 ${answeredCount} 問を解いて ${correctCount} 問正解`}
      </p>
    </li>
  );
}
