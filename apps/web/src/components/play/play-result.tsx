"use client";

import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Markdown } from "@/components/markdown";
import { ChoiceList } from "@/components/play/choice-list";
import { Eyebrow, Panel, Stat, Verdict } from "@/components/play/parts";
import { completeAttempt, getGetCurrentAttemptQueryKey } from "@/lib/api/generated/endpoints";
import type { QuizResult } from "@/lib/api/generated/model";
import type { FeedbackMode } from "@/lib/play/mode";

/**
 * 結果画面。
 *
 * 完了 API は完了済みの挑戦にも結果を返すため、表示のたびに呼んでよい（再読み込みで失敗しない）。
 * **学習モードでは間違えた問題だけ、模試モードでは全問の解説を出す。**
 * 模試モードは回答中に解説を見ていないため、ここが唯一の振り返りの場になる。
 */
export function PlayResult({ slug, attemptId, mode }: { slug: string; attemptId: string; mode: FeedbackMode }) {
  const queryClient = useQueryClient();
  const result = useQuery({
    queryKey: ["attempt-result", slug, attemptId],
    queryFn: ({ signal }) => completeAttempt(slug, attemptId, { signal }),
    staleTime: Infinity,
  });

  // 完了したので、中断中の挑戦としての表示を消す
  useEffect(() => {
    if (result.isSuccess) queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
  }, [result.isSuccess, queryClient, slug]);

  if (result.isPending) return <Skeleton className="h-80 w-full rounded-2xl" />;
  if (result.isError) return <ApiErrorAlert error={result.error} />;

  const { totalCount, answeredCount, correctCount, results } = result.data;
  const rate = totalCount === 0 ? 0 : Math.round((correctCount / totalCount) * 100);
  // 番号は出題順のまま出す。絞り込んだ一覧の中で振り直すと、何問目だったかが分からなくなる
  const numbered = results.map((quiz, index) => ({ quiz, number: index + 1 }));
  const reviewed = mode === "exam" ? numbered : numbered.filter(({ quiz }) => !quiz.isCorrect);

  return (
    <div className="space-y-8">
      <dl className="grid grid-cols-2 gap-3">
        <Stat label="正答率" value={`${rate}%`} />
        <Stat label="正解" value={`${correctCount} / ${totalCount} 問`} />
        {answeredCount < totalCount && (
          <p className="col-span-2 text-sm text-muted-foreground">未回答 {totalCount - answeredCount} 問</p>
        )}
      </dl>

      <section className="space-y-4">
        <h2 className="text-sm font-semibold tracking-tight">{mode === "exam" ? "答え合わせ" : "間違えた問題"}</h2>
        {reviewed.length === 0 ? (
          <Panel>
            <p className="text-sm text-muted-foreground">全問正解です。</p>
          </Panel>
        ) : (
          reviewed.map(({ quiz, number }) => <Review key={quiz.quizId} slug={slug} quiz={quiz} number={number} />)
        )}
      </section>

      <Button asChild size="lg" className="w-full rounded-full">
        <Link href={`/t/${slug}/play`}>もう一度</Link>
      </Button>
    </div>
  );
}

function Review({ slug, quiz, number }: { slug: string; quiz: QuizResult; number: number }) {
  const answered = quiz.selectedChoiceId != null;
  return (
    <Panel className="space-y-4 p-5">
      <div className="space-y-2">
        <div className="flex items-center gap-3">
          <Eyebrow>QUESTION {number}</Eyebrow>
          <Verdict correct={quiz.isCorrect} answered={answered} />
        </div>
        <p className="leading-snug font-semibold tracking-tight whitespace-pre-wrap">{quiz.question}</p>
      </div>
      <ChoiceList
        choices={quiz.choices}
        selectedId={quiz.selectedChoiceId}
        reveal={{ correctChoiceId: quiz.correctChoiceId }}
      />
      <div className="rounded-xl bg-muted/50 p-4 text-sm">
        <Markdown tenant={slug}>{quiz.explanation}</Markdown>
      </div>
    </Panel>
  );
}
