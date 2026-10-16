"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Panel } from "@/components/play/parts";
import { useGetReviewSummary } from "@/lib/api/generated/endpoints";
import { nextReviewLabel } from "@/lib/play/review";
import { useQuickStart } from "@/lib/play/use-quick-start";

/**
 * 今日の復習（DEV-178、ADR-0033）。次に出す日が来た問題を、範囲を選ばずにすぐ解ける。
 *
 * 方式は学習モード。忘れかけた問題は、解説を見ながら解き直すのが合う。
 * 件数が取れないときは何も出さない。出題の条件を選ぶ邪魔をしない。
 */
export function ReviewCard({ slug }: { slug: string }) {
  const summary = useGetReviewSummary(slug);
  const start = useQuickStart(slug, "learn", "review");

  if (!summary.data) return null;
  const { dueCount, nextDueOn, today } = summary.data;

  if (dueCount === 0) {
    return (
      <Panel className="space-y-1">
        <p className="font-semibold">今日の復習はありません</p>
        <p className="text-sm text-muted-foreground">
          {nextDueOn
            ? `次の復習は${nextReviewLabel(nextDueOn, today)}です。`
            : "解いた問題が、忘れたころにここへ出ます。間違えた問題は、その日のうちに出ます。"}
        </p>
      </Panel>
    );
  }

  return (
    <Panel className="space-y-3">
      <div className="flex flex-wrap items-center gap-4">
        <div className="min-w-0 flex-1 space-y-1">
          <p className="font-semibold">今日の復習 {dueCount} 問</p>
          <p className="text-sm text-muted-foreground">忘れかけた問題と、間違えた問題です。</p>
        </div>
        <Button className="rounded-full" disabled={start.isPending} onClick={() => start.start()}>
          {start.isPending ? "準備しています…" : "復習する"}
        </Button>
      </div>
      {start.conflict ? (
        <p className="text-sm text-muted-foreground">
          中断中のクイズがあります。上の「再開する」か「破棄する」を選んでから始めてください。
        </p>
      ) : (
        start.error && <ApiErrorAlert error={start.error} />
      )}
    </Panel>
  );
}
