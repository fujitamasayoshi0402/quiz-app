"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useListCategoryScores } from "@/lib/api/generated/endpoints";
import type { CategoryScore } from "@/lib/api/generated/model";
import { rateOf } from "@/lib/accuracy";
import { useRetryIncorrect } from "@/lib/play/use-retry-incorrect";

/**
 * カテゴリ別の正答率。
 *
 * 数えるのは、いま出題できるクイズへの最新の回答だけ（バックエンドが決める）。
 * 解いていないカテゴリも並べる。どこに手を付けていないかも、次に解くものを選ぶ手がかりになる。
 * 回答が少ないうちは率を出さない（`lib/accuracy.ts`）。間違えた問題があれば、その場で解き直せる（DEV-169）。
 */
export function CategoryScores({ slug }: { slug: string }) {
  const scores = useListCategoryScores(slug);

  if (scores.isPending) return <Skeleton className="h-40 w-full" />;
  if (scores.isError) return <ApiErrorAlert error={scores.error} />;
  if (scores.data.length === 0) {
    return <p className="text-sm text-muted-foreground">出題できるクイズがまだありません。</p>;
  }

  return (
    <ul className="divide-y rounded-lg border">
      {scores.data.map((score) => (
        <ScoreRow key={score.categoryId} slug={slug} score={score} />
      ))}
    </ul>
  );
}

function ScoreRow({ slug, score }: { slug: string; score: CategoryScore }) {
  const { categoryId, name, quizCount, answeredCount, correctCount } = score;
  const rate = rateOf(answeredCount, correctCount);
  // どちらも最新の回答で数えているので、差が「最後に答えたとき間違えた問題」の数になる。出題の incorrect と同じ
  const missedCount = answeredCount - correctCount;
  const progress = quizCount === 0 ? 0 : Math.round((answeredCount / quizCount) * 100);

  return (
    <li className="space-y-2 p-4">
      <div className="flex items-baseline justify-between gap-4">
        <span className="min-w-0 font-medium break-words">{name}</span>
        <span
          className={
            rate == null ? "shrink-0 text-xs text-muted-foreground" : "shrink-0 text-lg font-semibold tabular-nums"
          }
        >
          {answeredCount === 0 ? "—" : rate == null ? "回答が少ない" : `${rate}%`}
        </span>
      </div>
      {/* 棒は解いた割合。正答率は数字で出す。手を付けていない問題がどれだけ残っているかを見せる */}
      <Progress value={progress} aria-label={`${name} の解いた割合`} />
      <p className="text-xs text-muted-foreground">
        {answeredCount === 0
          ? `${quizCount} 問。まだ解いていません`
          : `${quizCount} 問中 ${answeredCount} 問を解いて ${correctCount} 問正解`}
      </p>
      {missedCount > 0 && <RetryButton slug={slug} categoryId={categoryId} missedCount={missedCount} />}
    </li>
  );
}

/**
 * そのカテゴリの「間違えた問題」を、すぐ解き直す（DEV-169）。
 * 方式は学習モード。間違えた問題は、解説を見ながら解き直すのが合う
 */
function RetryButton({ slug, categoryId, missedCount }: { slug: string; categoryId: string; missedCount: number }) {
  const start = useRetryIncorrect(slug, "learn");

  return (
    <div className="space-y-2 pt-1">
      {start.conflict ? (
        <p className="text-sm text-muted-foreground">
          中断中のクイズがあります。「解く」から、再開するか破棄するかを選んでください。
        </p>
      ) : (
        start.error && <ApiErrorAlert error={start.error} />
      )}
      <Button variant="outline" size="sm" disabled={start.isPending} onClick={() => start.retry({ categoryId })}>
        {start.isPending ? "準備しています…" : `間違えた ${missedCount} 問を解き直す`}
      </Button>
    </div>
  );
}
