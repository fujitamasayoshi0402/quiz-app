"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Info } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Markdown } from "@/components/markdown";
import { BookmarkToggle } from "@/components/play/bookmark-toggle";
import { ChoiceList } from "@/components/play/choice-list";
import { AiSourceNote, Eyebrow, Panel, ProgressLine, Verdict } from "@/components/play/parts";
import { SwipeSession } from "@/components/play/swipe-session";
import { ApiError } from "@/lib/api/fetcher";
import { useAnswerQuiz, useResumeAttempt } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView } from "@/lib/api/generated/model";
import type { FeedbackMode } from "@/lib/play/mode";

/**
 * 回答画面。挑戦をサーバーから読み直して始めるため、再読み込みや別端末からの再開でも同じ位置に戻る。
 * **現在位置は保存しない。** 出題順と回答済みの一覧から、最初の未回答を導く（要件定義）。
 */
export function PlaySession({ slug, attemptId, mode }: { slug: string; attemptId: string; mode: FeedbackMode }) {
  // 画面を離れたらキャッシュを捨てる（gcTime: 0）。残すと、出題の画面から再開したときに、
  // 最初に開いたときの回答済みの一覧から始まり、答えた問題がまた出る
  const attempt = useResumeAttempt(slug, attemptId, { query: { staleTime: Infinity, gcTime: 0 } });

  if (attempt.isPending) return <Skeleton className="h-80 w-full rounded-2xl" />;
  if (attempt.isError) return <ApiErrorAlert error={attempt.error} />;
  if (mode === "swipe" && attempt.data.status === "in_progress")
    return <SwipeSession slug={slug} attempt={attempt.data} />;
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
      <Panel className="space-y-4">
        <p className="font-semibold">このクイズは終了しています</p>
        <Button asChild className="rounded-full">
          <Link href={resultUrl}>結果を見る</Link>
        </Button>
      </Panel>
    );
  }

  // 出題後に削除・非公開になったクイズ（409）は、答えられないので飛ばせるようにする
  const unanswerable = answer.error instanceof ApiError && answer.error.status === 409;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <ProgressLine value={done.size} max={total} label="回答した問題" />
        <span className="text-xs font-medium text-muted-foreground tabular-nums">
          {position} / {total} 問
        </span>
      </div>

      {attempt.excludedCount > 0 && (
        <Alert className="rounded-2xl">
          <Info />
          <AlertDescription>出題後に削除・非公開になった {attempt.excludedCount} 問を除いています。</AlertDescription>
        </Alert>
      )}

      <div className="space-y-3">
        <Eyebrow>
          QUESTION {position}・{mode === "learn" ? "学習モード" : "模試モード"}
        </Eyebrow>
        <p data-slot="question" className="text-xl leading-snug font-semibold tracking-tight whitespace-pre-wrap">
          {current.question}
        </p>
      </div>

      <ChoiceList
        choices={current.choices}
        selectedId={selectedId}
        onSelect={setSelectedId}
        reveal={feedback ? { correctChoiceId: feedback.correctChoiceId } : undefined}
      />
      {feedback && <Explanation slug={slug} quizId={current.id} result={feedback} />}
      {answer.isError && <ApiErrorAlert error={answer.error} />}

      <div className="flex justify-end">
        {feedback || unanswerable ? (
          <Button size="lg" className="rounded-full px-8" onClick={() => advance(current.id)}>
            {done.size + 1 >= total ? "結果を見る" : "次の問題へ"}
          </Button>
        ) : (
          <Button size="lg" className="rounded-full px-8" onClick={submit} disabled={!selectedId || answer.isPending}>
            回答する
          </Button>
        )}
      </div>
    </div>
  );
}

function Explanation({ slug, quizId, result }: { slug: string; quizId: string; result: AnswerResult }) {
  return (
    <Panel className="animate-in space-y-2 duration-300 fade-in slide-in-from-bottom-2">
      <div className="flex items-center gap-2">
        <Verdict correct={result.isCorrect} />
        <BookmarkToggle slug={slug} quizId={quizId} className="ml-auto" />
      </div>
      <Markdown tenant={slug}>{result.explanation}</Markdown>
      <AiSourceNote sourceUrl={result.sourceUrl} className="border-t pt-2" />
    </Panel>
  );
}
