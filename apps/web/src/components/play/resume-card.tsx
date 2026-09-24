"use client";

import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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
    <Card className="border-primary/40">
      <CardHeader>
        <CardTitle>中断中のクイズがあります</CardTitle>
        <CardDescription>
          {total} 問中 {answered} 問回答済み
        </CardDescription>
      </CardHeader>
      <CardContent className="flex gap-2">
        <Button
          onClick={() =>
            router.push(`/t/${slug}/play/session?attempt=${attempt.id}&mode=${recallMode(attempt.id)}`)
          }
        >
          再開する
        </Button>
        <Button
          variant="outline"
          disabled={abandon.isPending}
          onClick={() => abandon.mutate({ slug, attemptId: attempt.id })}
        >
          破棄する
        </Button>
      </CardContent>
    </Card>
  );
}
