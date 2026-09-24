"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Info } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { ChoiceList } from "@/components/play/choice-list";
import { ApiError } from "@/lib/api/fetcher";
import { useAnswerQuiz, useResumeAttempt } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView } from "@/lib/api/generated/model";
import type { FeedbackMode } from "@/lib/play/mode";

/**
 * 回答画面。挑戦をサーバーから読み直して始めるため、再読み込みや別端末からの再開でも同じ位置に戻る。
 * **現在位置は保存しない。** 出題順と回答済みの一覧から、最初の未回答を導く（要件定義）。
 */
export function PlaySession({ slug, attemptId, mode }: { slug: string; attemptId: string; mode: FeedbackMode }) {
  const attempt = useResumeAttempt(slug, attemptId, { query: { staleTime: Infinity } });

  if (attempt.isPending) return <Skeleton className="h-80 w-full" />;
  if (attempt.isError) return <ApiErrorAlert error={attempt.error} />;
  return <Session slug={slug} attempt={attempt.data} mode={mode} />;
}

function Session({ slug, attempt, mode }: { slug: string; attempt: AttemptView; mode: FeedbackMode }) {
  const router = useRouter();
  const resultUrl = `/t/${slug}/play/result?attempt=${attempt.id}&mode=${mode}`;

  const [done, setDone] = useState(() => new Set(attempt.answeredQuizIds));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<AnswerResult | null>(null);

  const answer = useAnswerQuiz();

  const total = attempt.quizzes.length;
  const current = attempt.quizzes.find((quiz) => !done.has(quiz.id));
  const position = attempt.quizzes.findIndex((quiz) => quiz.id === current?.id) + 1;

  const advance = (quizId: string) => {
    const next = new Set(done).add(quizId);
    setDone(next);
    setSelectedId(null);
    setFeedback(null);
    answer.reset();
    if (next.size >= total) router.push(resultUrl);
  };

  const submit = () => {
    if (!current || !selectedId) return;
    answer.mutate(
      { slug, attemptId: attempt.id, data: { quizId: current.id, choiceId: selectedId } },
      {
        // 学習モードはその場で答え合わせをする。模試モードは正誤を伏せて次へ進む
        onSuccess: (result) => (mode === "learn" ? setFeedback(result) : advance(current.id)),
      },
    );
  };

  if (attempt.status !== "in_progress" || !current) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>このクイズは終了しています</CardTitle>
        </CardHeader>
        <CardFooter>
          <Button asChild>
            <Link href={resultUrl}>結果を見る</Link>
          </Button>
        </CardFooter>
      </Card>
    );
  }

  // 出題後に削除・非公開になったクイズ（409）は、答えられないので飛ばせるようにする
  const unanswerable = answer.error instanceof ApiError && answer.error.status === 409;

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <div className="text-muted-foreground flex justify-between text-sm">
          <span>
            {position} / {total} 問
          </span>
          <span>{mode === "learn" ? "学習モード" : "模試モード"}</span>
        </div>
        <Progress value={(done.size / total) * 100} />
      </div>

      {attempt.excludedCount > 0 && (
        <Alert>
          <Info />
          <AlertDescription>
            出題後に削除・非公開になった {attempt.excludedCount} 問を除いています。
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-lg leading-relaxed font-medium whitespace-pre-wrap">{current.question}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <ChoiceList
            choices={current.choices}
            selectedId={selectedId}
            onSelect={setSelectedId}
            reveal={feedback ? { correctChoiceId: feedback.correctChoiceId } : undefined}
          />
          {feedback && <Explanation result={feedback} />}
          {answer.isError && <ApiErrorAlert error={answer.error} />}
        </CardContent>
        <CardFooter className="justify-end">
          {feedback || unanswerable ? (
            <Button onClick={() => advance(current.id)}>{done.size + 1 >= total ? "結果を見る" : "次の問題へ"}</Button>
          ) : (
            <Button onClick={submit} disabled={!selectedId || answer.isPending}>
              回答する
            </Button>
          )}
        </CardFooter>
      </Card>
    </div>
  );
}

function Explanation({ result }: { result: AnswerResult }) {
  return (
    <div className="space-y-2 rounded-lg border p-4">
      <p className={result.isCorrect ? "font-semibold text-emerald-700" : "font-semibold text-red-700"}>
        {result.isCorrect ? "正解" : "不正解"}
      </p>
      <p className="text-sm leading-relaxed whitespace-pre-wrap">{result.explanation}</p>
    </div>
  );
}
