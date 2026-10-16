"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { OptionGroup } from "@/components/play/option-group";
import { Panel } from "@/components/play/parts";
import { ResumeCard } from "@/components/play/resume-card";
import { ApiError } from "@/lib/api/fetcher";
import {
  getGetCurrentAttemptQueryKey,
  useListBookmarks,
  useListPlayableCategories,
  useStartAttempt,
} from "@/lib/api/generated/endpoints";
import { type FeedbackMode, parseMode, rememberMode } from "@/lib/play/mode";
import { countInRange, rangeOptions, toCriteria } from "@/lib/play/range";
import { SCOPES } from "@/lib/play/scope";

const ORDERS = [
  { value: "random", label: "ランダム" },
  { value: "registered", label: "登録順", hint: "レベルの低い順" },
];

/** 画面の初期値は 10 問（要件定義）。「すべて」はサーバーの上限まで出す */
const LIMITS = [
  { value: "5", label: "5 問" },
  { value: "10", label: "10 問" },
  { value: "20", label: "20 問" },
  { value: "all", label: "すべて" },
];

const MODES = [
  { value: "learn", label: "学習モード", hint: "1 問ごとに正誤と解説を見る" },
  { value: "exam", label: "模試モード", hint: "最後にまとめて答え合わせする" },
  { value: "swipe", label: "テンポモード", hint: "縦にスワイプして次々に解く。スマホ向け" },
];

/** 出題条件の選択。カテゴリ → 範囲 → 出題対象・並び・出題数 → フィードバック方式の順に選ぶ。 */
export function PlaySetup({ slug }: { slug: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const categories = useListPlayableCategories(slug);
  const bookmarks = useListBookmarks(slug);

  const [categoryId, setCategoryId] = useState<string>();
  const [rangeValue, setRangeValue] = useState("all");
  const [scope, setScope] = useState("all");
  const [order, setOrder] = useState("random");
  const [limit, setLimit] = useState("10");
  const [mode, setMode] = useState<FeedbackMode>("learn");

  const start = useStartAttempt({
    mutation: {
      onSuccess: (attempt) => {
        rememberMode(attempt.id, mode);
        queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
        router.push(`/t/${slug}/play/session?attempt=${attempt.id}&mode=${mode}`);
      },
    },
  });

  if (categories.isPending) return <Skeleton className="h-64 w-full rounded-2xl" />;
  if (categories.isError) return <ApiErrorAlert error={categories.error} />;

  if (categories.data.length === 0) {
    return (
      <Panel className="space-y-1">
        <p className="font-semibold">出題できるクイズがありません</p>
        <p className="text-sm text-muted-foreground">管理者がクイズを公開すると、ここから解けるようになります。</p>
      </Panel>
    );
  }

  const category = categories.data.find((c) => c.id === categoryId);
  const ranges = category ? rangeOptions(category) : [];
  const range = ranges.find((r) => r.value === rangeValue) ?? ranges[0];
  // 「あとで見直す問題」に、選んだ範囲で何問あるかを添える。数えられないうちは添えない
  const bookmarked =
    category && range && bookmarks.data ? countInRange(bookmarks.data.items, category, range.range) : undefined;
  const scopes = SCOPES.map((option) =>
    option.value === "bookmarked" && bookmarked !== undefined
      ? { ...option, hint: `${option.hint}・この範囲に ${bookmarked} 問` }
      : option,
  );

  const submit = (discardInProgress = false) => {
    if (!category || !range) return;
    start.mutate({
      slug,
      data: {
        ...toCriteria(category.id, range.range),
        scope,
        order,
        limit: limit === "all" ? undefined : Number(limit),
        discardInProgress,
      },
    });
  };

  // 中断中の挑戦があると 409 が返る。黙って破棄せず、再開か破棄かを選ばせる
  const conflict = start.error instanceof ApiError && start.error.status === 409;

  return (
    <div className="space-y-8">
      <ResumeCard slug={slug} />

      <Section title="カテゴリ">
        <OptionGroup
          name="category"
          columns={2}
          value={categoryId ?? ""}
          onChange={(value) => {
            setCategoryId(value);
            setRangeValue("all");
          }}
          options={categories.data.map((c) => ({
            value: c.id,
            label: c.name,
            hint: c.description ? `${c.description}・${c.quizCount} 問` : `${c.quizCount} 問`,
          }))}
        />
      </Section>

      {category && (
        <>
          <Section title="範囲">
            <OptionGroup name="range" value={range?.value ?? "all"} onChange={setRangeValue} options={ranges} />
          </Section>
          <Section title="出題対象">
            <OptionGroup name="scope" value={scope} onChange={setScope} options={scopes} />
          </Section>
          <Section title="並び">
            <OptionGroup name="order" columns={2} value={order} onChange={setOrder} options={ORDERS} />
          </Section>
          <Section title="出題数">
            <OptionGroup name="limit" columns={4} value={limit} onChange={setLimit} options={LIMITS} />
          </Section>
          <Section title="答え合わせ">
            <OptionGroup
              name="mode"
              columns={2}
              value={mode}
              onChange={(value) => setMode(parseMode(value))}
              options={MODES}
            />
          </Section>

          {conflict ? (
            <Panel className="space-y-4 border-amber-500/50 bg-amber-50 dark:bg-amber-950/30">
              <div className="space-y-1">
                <p className="font-semibold">中断中のクイズがあります</p>
                <p className="text-sm text-muted-foreground">新しく始めると、中断中のクイズは破棄されます。</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button className="rounded-full" onClick={() => submit(true)} disabled={start.isPending}>
                  破棄して新しく始める
                </Button>
                <Button variant="outline" className="rounded-full" onClick={() => start.reset()}>
                  やめる
                </Button>
              </div>
            </Panel>
          ) : (
            <>
              {start.isError && <ApiErrorAlert error={start.error} />}
              <Button size="lg" className="w-full rounded-full" onClick={() => submit()} disabled={start.isPending}>
                {start.isPending ? "準備しています…" : "始める"}
              </Button>
            </>
          )}
        </>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}
