"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Progress } from "@/components/ui/progress";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Markdown } from "@/components/markdown";
import { ChoiceList } from "@/components/play/choice-list";
import { useAnswerQuiz } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView, DeliveredQuiz } from "@/lib/api/generated/model";
import { cn } from "@/lib/utils";

type Answered = { choiceId: string; result?: AnswerResult; error?: unknown };

/**
 * テンポモード（DEV-75）。1 画面に 1 問を並べ、縦にスワイプして次々に解く。
 *
 * - スワイプはブラウザのスクロールスナップに任せる。ライブラリは使わない
 * - 選択肢を押したら、その場で回答して正誤を出す。解説は冒頭だけを出し、押すと全文を開く
 * - 答えずにスワイプすれば飛ばす。飛ばした問題は未回答のまま、結果画面に出る
 * - 出題は挑戦を始めたときに全問届いているため、次の問題を先に取る必要はない
 * - スワイプのほか、キーボード（↑↓、1〜4）と「次へ」のボタンでも進める
 */
export function SwipeSession({ slug, attempt }: { slug: string; attempt: AttemptView }) {
  const router = useRouter();
  const resultUrl = `/t/${slug}/play/result?attempt=${attempt.id}&mode=swipe`;

  // 開いたときに未回答だったものだけを並べる。再開したときは、続きから始まる
  const [quizzes] = useState(() => attempt.quizzes.filter((quiz) => !attempt.answeredQuizIds.includes(quiz.id)));
  const alreadyAnswered = attempt.quizzes.length - quizzes.length;

  const [answers, setAnswers] = useState<Record<string, Answered>>({});
  const [current, setCurrent] = useState(0);
  const sections = useRef<(HTMLElement | null)[]>([]);
  const answer = useAnswerQuiz();

  const answeredCount = alreadyAnswered + Object.values(answers).filter((a) => a.result).length;
  const total = attempt.quizzes.length;

  const scrollTo = useCallback((index: number) => {
    sections.current[Math.max(0, Math.min(index, sections.current.length - 1))]?.scrollIntoView({
      behavior: "smooth",
    });
  }, []);

  const choose = useCallback(
    (quiz: DeliveredQuiz, choiceId: string) => {
      const previous = answers[quiz.id];
      if (previous && previous.error === undefined) return;
      setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId } }));
      // mutate に渡したコールバックは、最後の呼び出しの分しか呼ばれない。続けて答えたときに取りこぼさないよう、Promise で受ける
      answer
        .mutateAsync({ slug, attemptId: attempt.id, data: { quizId: quiz.id, choiceId } })
        .then((result) => setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId, result } })))
        .catch((error: unknown) => setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId, error } })));
    },
    [answer, answers, attempt.id, slug],
  );

  // 画面の中央に来た問題を「いまの問題」にする
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) setCurrent(Number((entry.target as HTMLElement).dataset.index));
        }
      },
      { threshold: 0.6 },
    );
    sections.current.forEach((section) => section && observer.observe(section));
    return () => observer.disconnect();
  }, [quizzes.length]);

  // 後ろのページを一緒にスクロールさせない
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("[role=dialog]")) return;
      if (["ArrowDown", "PageDown", "j"].includes(event.key)) {
        event.preventDefault();
        scrollTo(current + 1);
      } else if (["ArrowUp", "PageUp", "k"].includes(event.key)) {
        event.preventDefault();
        scrollTo(current - 1);
      } else if (/^[1-4]$/.test(event.key)) {
        const quiz = quizzes[current];
        const choice = quiz?.choices[Number(event.key) - 1];
        if (quiz && choice) choose(quiz, choice.id);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [choose, current, quizzes, scrollTo]);

  return (
    <div className="fixed inset-0 z-40 bg-background">
      <header className="absolute inset-x-0 top-0 z-10 bg-background/90 px-4 py-2 backdrop-blur">
        <div className="mx-auto flex max-w-xl items-center gap-3">
          <Button variant="ghost" size="icon" asChild>
            <Link href={`/t/${slug}/play`} aria-label="中断して出題の画面へ戻る">
              <X />
            </Link>
          </Button>
          <Progress value={(answeredCount / total) * 100} className="flex-1" />
          <span className="text-sm text-muted-foreground tabular-nums">
            {Math.min(alreadyAnswered + current + 1, total)} / {total}
          </span>
        </div>
      </header>

      <div className="h-full snap-y snap-mandatory overflow-y-auto overscroll-contain">
        {quizzes.map((quiz, index) => (
          <section
            key={quiz.id}
            ref={(element) => {
              sections.current[index] = element;
            }}
            data-index={index}
            aria-label={`${alreadyAnswered + index + 1} 問目`}
            className="flex h-dvh snap-start snap-always flex-col px-4 pt-16 pb-4"
          >
            <QuizCard
              slug={slug}
              quiz={quiz}
              answered={answers[quiz.id]}
              onChoose={(choiceId) => choose(quiz, choiceId)}
              onNext={() => scrollTo(index + 1)}
            />
          </section>
        ))}
        <section
          ref={(element) => {
            sections.current[quizzes.length] = element;
          }}
          data-index={quizzes.length}
          className="flex h-dvh snap-start snap-always flex-col items-center justify-center gap-4 px-4 text-center"
        >
          <p className="text-2xl font-semibold">おつかれさまでした</p>
          <p className="text-muted-foreground">
            {total} 問中 {answeredCount} 問に回答しました
            {answeredCount < total && (
              <>
                <br />
                飛ばした {total - answeredCount} 問は、未回答として結果に出ます
              </>
            )}
          </p>
          <Button size="lg" onClick={() => router.push(resultUrl)}>
            結果を見る
          </Button>
        </section>
      </div>
    </div>
  );
}

function QuizCard({
  slug,
  quiz,
  answered,
  onChoose,
  onNext,
}: {
  slug: string;
  quiz: DeliveredQuiz;
  answered?: Answered;
  onChoose: (choiceId: string) => void;
  onNext: () => void;
}) {
  const [explanationOpen, setExplanationOpen] = useState(false);
  const result = answered?.result;

  return (
    <div className="mx-auto flex min-h-0 w-full max-w-xl flex-1 flex-col">
      <div className="flex min-h-0 flex-1 flex-col justify-center gap-4 overflow-y-auto">
        <p className="text-lg leading-relaxed font-medium whitespace-pre-wrap">{quiz.question}</p>
        <ChoiceList
          choices={quiz.choices}
          selectedId={answered?.choiceId}
          // 押したらすぐに回答する。結果が届くまでは押し直せない。失敗したときは選び直せる
          onSelect={answered && answered.error === undefined ? undefined : onChoose}
          reveal={result ? { correctChoiceId: result.correctChoiceId } : undefined}
        />
        {answered?.error !== undefined && <ApiErrorAlert error={answered.error} />}
        {result && (
          <div className="space-y-2 rounded-lg border p-3">
            <p className={cn("font-semibold", result.isCorrect ? "text-emerald-700" : "text-red-700")}>
              {result.isCorrect ? "正解" : "不正解"}
            </p>
            {/* 冒頭だけを出す。テンポを保ちつつ、読みたい人は全文を開ける */}
            <div className="max-h-20 overflow-hidden mask-b-from-40%">
              <Markdown tenant={slug}>{result.explanation}</Markdown>
            </div>
            <Button variant="link" className="h-auto p-0" onClick={() => setExplanationOpen(true)}>
              解説を全部読む
            </Button>
            <Dialog open={explanationOpen} onOpenChange={setExplanationOpen}>
              <DialogContent className="max-h-[85dvh] overflow-y-auto">
                <DialogHeader>
                  <DialogTitle>解説</DialogTitle>
                </DialogHeader>
                <Markdown tenant={slug}>{result.explanation}</Markdown>
              </DialogContent>
            </Dialog>
          </div>
        )}
      </div>
      <Button variant="ghost" className="mt-2 self-center text-muted-foreground" onClick={onNext}>
        <ChevronDown />
        {answered ? "次へ" : "飛ばす"}
      </Button>
    </div>
  );
}
