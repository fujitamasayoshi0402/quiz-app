"use client";

import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Panel, ProgressLine } from "@/components/play/parts";
import { useAbandonAttempt, useGetCurrentAttempt } from "@/lib/api/generated/endpoints";
import { recallMode } from "@/lib/play/mode";

/**
 * 中断中の挑戦があれば、再開か破棄を選べるようにする。
 * 挑戦はサーバーに保存されているため、別の端末で始めたものもここに出る。
 */
export function ResumeCard({ slug }: { slug: string }) {
  const router = useRouter();
  const current = useGetCurrentAttempt(slug);
  const abandon = useAbandonAttempt({ mutation: { onSuccess: () => current.refetch() } });

  const attempt = current.data;
  if (!attempt) return null;

  const answered = attempt.answeredQuizIds.length;
  const total = attempt.quizzes.length;

  return (
    <Panel className="flex flex-wrap items-center gap-4">
      <div className="min-w-0 flex-1 space-y-1">
        <p className="font-semibold">中断中のクイズがあります</p>
        <div className="flex items-center gap-3">
          <ProgressLine value={answered} max={total} label="回答した問題" />
          <span className="text-xs font-medium text-muted-foreground tabular-nums">
            {total} 問中 {answered} 問回答済み
          </span>
        </div>
      </div>
      <div className="flex gap-2">
        <Button
          className="rounded-full"
          onClick={() => router.push(`/t/${slug}/play/session?attempt=${attempt.id}&mode=${recallMode(attempt.id)}`)}
        >
          再開する
        </Button>
        <Button
          variant="outline"
          className="rounded-full"
          disabled={abandon.isPending}
          onClick={() => abandon.mutate({ slug, attemptId: attempt.id })}
        >
          破棄する
        </Button>
      </div>
    </Panel>
  );
}
