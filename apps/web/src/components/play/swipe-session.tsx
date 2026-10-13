"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, ChevronRight, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Markdown } from "@/components/markdown";
import { ChoiceList } from "@/components/play/choice-list";
import { Eyebrow, ProgressLine, Stat, Verdict } from "@/components/play/parts";
import { useAnswerQuiz } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView, DeliveredQuiz } from "@/lib/api/generated/model";
import { cn } from "@/lib/utils";

type Answered = { choiceId: string; result?: AnswerResult; error?: unknown };

/**
 * テンポモード（DEV-75）。1 画面に 1 問を並べ、縦にスワイプして次々に解く。
 *
 * - スワイプはブラウザのスクロールスナップに任せる。ライブラリは使わない
 * - 選択肢を押したら、その場で回答して正誤を出す。解説は冒頭だけを出し、押すと下からシートで全文を開く。
 *   シートは後ろをぼかさない。問題文と選択肢を見ながら読める
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

  const correctCount = Object.values(answers).filter((a) => a.result?.isCorrect).length;

  return (
    <div className="fixed inset-0 z-40 bg-linear-to-b from-background via-background to-muted/70">
      <header className="absolute inset-x-0 top-0 z-10 bg-linear-to-b from-background via-background/80 to-transparent px-4 pt-3 pb-6">
        <div className="mx-auto flex max-w-xl items-center gap-3">
          <Button variant="ghost" size="icon" className="rounded-full text-muted-foreground" asChild>
            <Link href={`/t/${slug}/play`} aria-label="中断して出題の画面へ戻る">
              <X />
            </Link>
          </Button>
          <ProgressLine value={answeredCount} max={total} label="回答した問題" />
          <span className="w-12 text-right text-xs font-medium text-muted-foreground tabular-nums">
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
            className="flex h-dvh snap-start snap-always flex-col px-5 pt-20 pb-5"
          >
            <QuizCard
              slug={slug}
              number={alreadyAnswered + index + 1}
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
          className="flex h-dvh snap-start snap-always flex-col items-center justify-center gap-6 px-5 text-center"
        >
          <div className="space-y-2">
            <Eyebrow>FINISHED</Eyebrow>
            <p className="text-3xl font-semibold tracking-tight">おつかれさまでした</p>
          </div>
          <dl className="grid w-full max-w-xs grid-cols-2 gap-3">
            <Stat label="回答" value={`${answeredCount} / ${total}`} />
            <Stat label="今回の正解" value={`${correctCount}`} />
          </dl>
          {answeredCount < total && (
            <p className="text-sm text-muted-foreground">
              飛ばした {total - answeredCount} 問は、未回答として結果に出ます
            </p>
          )}
          <Button size="lg" className="rounded-full px-8" onClick={() => router.push(resultUrl)}>
            結果を見る
          </Button>
        </section>
      </div>
    </div>
  );
}

function QuizCard({
  slug,
  number,
  quiz,
  answered,
  onChoose,
  onNext,
}: {
  slug: string;
  number: number;
  quiz: DeliveredQuiz;
  answered?: Answered;
  onChoose: (choiceId: string) => void;
  onNext: () => void;
}) {
  const [explanationOpen, setExplanationOpen] = useState(false);
  const result = answered?.result;
  // 押したらすぐに回答する。結果が届くまでは押し直せない。失敗したときは選び直せる
  const locked = answered !== undefined && answered.error === undefined;

  return (
    // 上に寄せる。中央に寄せると、正誤と解説が出たときに問題文が動く
    <div className="mx-auto flex min-h-0 w-full max-w-xl flex-1 flex-col">
      <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto pt-[4dvh]">
        <div className="space-y-3">
          <Eyebrow>QUESTION {number}</Eyebrow>
          <p data-slot="question" className="text-xl leading-snug font-semibold tracking-tight whitespace-pre-wrap">
            {quiz.question}
          </p>
        </div>

        <ChoiceList
          choices={quiz.choices}
          selectedId={answered?.choiceId}
          onSelect={locked ? undefined : onChoose}
          reveal={result ? { correctChoiceId: result.correctChoiceId } : undefined}
          shortcuts
        />

        {answered?.error !== undefined && <ApiErrorAlert error={answered.error} />}
        {result && (
          // カードのどこを押しても開く。キーボードでは「解説を読む」のボタンで開く
          <div
            onClick={() => setExplanationOpen(true)}
            className="animate-in cursor-pointer space-y-2 rounded-2xl border border-foreground/10 bg-card p-4 shadow-sm duration-300 fade-in slide-in-from-bottom-2 hover:shadow-md"
          >
            <div className="flex items-center gap-2">
              <Verdict correct={result.isCorrect} />
              <Button
                variant="ghost"
                size="sm"
                className="ml-auto h-auto gap-0 px-2 py-1 text-xs text-muted-foreground"
              >
                解説を読む
                <ChevronRight className="size-4" />
              </Button>
            </div>
            {/* 冒頭だけを出す。テンポを保ちつつ、読みたい人は下から全文を開ける。中のリンクは押させない */}
            <div className="pointer-events-none max-h-16 overflow-hidden mask-b-from-30% text-sm text-muted-foreground">
              <Markdown tenant={slug}>{result.explanation}</Markdown>
            </div>
          </div>
        )}
      </div>

      <Button
        variant="ghost"
        className={cn("mt-3 self-center rounded-full text-muted-foreground", result && "text-foreground")}
        onClick={onNext}
      >
        <ChevronDown className={cn(result && "motion-safe:animate-bounce")} />
        {answered ? "次へ" : "飛ばす"}
      </Button>

      {result && (
        <Sheet open={explanationOpen} onOpenChange={setExplanationOpen}>
          <SheetContent aria-describedby={undefined}>
            <SheetHeader>
              <SheetTitle>解説</SheetTitle>
            </SheetHeader>
            <SheetBody>
              <Markdown tenant={slug}>{result.explanation}</Markdown>
            </SheetBody>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
