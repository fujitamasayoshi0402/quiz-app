"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Panel } from "@/components/play/parts";
import { useQuickStart } from "@/lib/play/use-quick-start";

/** DEV-224。デモパックの編集カテゴリを入口にする。環境ごとに異なる ID は API から取る。 */
export function DemoStarterCard({
  slug,
  categories,
}: {
  slug: string;
  categories: { id: string; name: string; quizCount: number }[];
}) {
  const start = useQuickStart(slug, "learn", "all", { order: "registered", limit: 5 });
  const category = categories.find((item) => item.name === "まずは5問" && item.quizCount === 5);
  if (slug !== "demo" || !category) return null;

  return (
    <Panel className="space-y-4 border-primary/30 bg-primary/5">
      <div className="space-y-2">
        <h2 className="text-xl font-semibold">まずは5問</h2>
        <p className="text-sm text-muted-foreground">
          お菓子・ブランド・映画から、誰かに話したくなる雑学を集めました。約3分で、1問ごとに答えと解説を楽しめます。
        </p>
      </div>
      <Button
        className="w-full rounded-full"
        size="lg"
        disabled={start.isPending}
        onClick={() => start.start({ categoryId: category.id })}
      >
        {start.isPending ? "準備しています…" : "おすすめの5問を始める"}
      </Button>
      {start.conflict ? (
        <p role="status" className="text-sm text-muted-foreground">
          中断中のクイズがあります。下の「再開する」か「破棄する」を選んでから始めてください。
        </p>
      ) : (
        start.error && <ApiErrorAlert error={start.error} />
      )}
    </Panel>
  );
}
