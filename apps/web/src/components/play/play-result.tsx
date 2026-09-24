"use client";

import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { ChoiceList } from "@/components/play/choice-list";
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

  if (result.isPending) return <Skeleton className="h-80 w-full" />;
  if (result.isError) return <ApiErrorAlert error={result.error} />;

  const { totalCount, answeredCount, correctCount, results } = result.data;
  const rate = totalCount === 0 ? 0 : Math.round((correctCount / totalCount) * 100);
  // 番号は出題順のまま出す。絞り込んだ一覧の中で振り直すと、何問目だったかが分からなくなる
  const numbered = results.map((quiz, index) => ({ quiz, number: index + 1 }));
  const reviewed = mode === "exam" ? numbered : numbered.filter(({ quiz }) => !quiz.isCorrect);

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardDescription>正答率</CardDescription>
          <CardTitle className="text-4xl">{rate}%</CardTitle>
        </CardHeader>
        <CardContent className="text-muted-foreground space-y-1 text-sm">
          <p>
            {totalCount} 問中 {correctCount} 問正解
          </p>
          {answeredCount < totalCount && <p>未回答 {totalCount - answeredCount} 問</p>}
        </CardContent>
      </Card>

      <section className="space-y-3">
        <h2 className="font-semibold">{mode === "exam" ? "答え合わせ" : "間違えた問題"}</h2>
        {reviewed.length === 0 ? (
          <p className="text-muted-foreground text-sm">全問正解です。</p>
        ) : (
          reviewed.map(({ quiz, number }) => <Review key={quiz.quizId} quiz={quiz} number={number} />)
        )}
      </section>

      <Button asChild size="lg" className="w-full">
        <Link href={`/t/${slug}/play`}>もう一度</Link>
      </Button>
    </div>
  );
}

function Review({ quiz, number }: { quiz: QuizResult; number: number }) {
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground text-sm">Q{number}</span>
          {quiz.selectedChoiceId == null ? (
            <Badge variant="secondary">未回答</Badge>
          ) : quiz.isCorrect ? (
            <Badge className="bg-emerald-600">正解</Badge>
          ) : (
            <Badge variant="destructive">不正解</Badge>
          )}
        </div>
        <CardTitle className="leading-relaxed font-medium whitespace-pre-wrap">{quiz.question}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <ChoiceList
          choices={quiz.choices}
          selectedId={quiz.selectedChoiceId}
          reveal={{ correctChoiceId: quiz.correctChoiceId }}
        />
        <p className="text-sm leading-relaxed whitespace-pre-wrap">{quiz.explanation}</p>
      </CardContent>
    </Card>
  );
}
