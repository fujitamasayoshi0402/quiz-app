"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  getGetRankingQueryKey,
  useGetRanking,
  useLeaveRanking,
  useParticipateInRanking,
} from "@/lib/api/generated/endpoints";
import type { MyRanking, RankingEntry } from "@/lib/api/generated/model";
import {
  accuracy,
  PERIODS,
  type Period,
  type ParticipationFormInput,
  ParticipationFormSchema,
  type ParticipationFormValues,
} from "@/lib/play/ranking";
import { cn } from "@/lib/utils";

/**
 * テナント内のランキング。
 *
 * **載るのは、参加を選んで名前を決めた人だけ。** 参加していなくても見られる。
 * 並びは期間内に正解したクイズの数（同じクイズは 1 回だけ）で、同点は正答率の高い順（バックエンドが決める）。
 */
export function RankingBoard({ slug }: { slug: string }) {
  const [period, setPeriod] = useState<Period>("30d");
  const ranking = useGetRanking(slug, { period });

  return (
    <div className="space-y-6">
      {ranking.data && <ParticipationCard slug={slug} me={ranking.data.me} />}

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">正解したクイズの数</h2>
          <PeriodSwitch value={period} onChange={setPeriod} />
        </div>
        {ranking.isPending ? (
          <Skeleton className="h-60 w-full" />
        ) : ranking.isError ? (
          <ApiErrorAlert error={ranking.error} />
        ) : (
          <RankingTable entries={ranking.data.entries} me={ranking.data.me} />
        )}
      </section>
    </div>
  );
}

function PeriodSwitch({ value, onChange }: { value: Period; onChange: (value: Period) => void }) {
  return (
    <div role="group" aria-label="期間" className="inline-flex rounded-lg bg-muted p-1">
      {PERIODS.map((period) => (
        <button
          key={period.value}
          type="button"
          aria-pressed={value === period.value}
          onClick={() => onChange(period.value)}
          className={cn(
            "rounded-md px-3 py-1 text-sm text-muted-foreground",
            value === period.value && "bg-background text-foreground shadow-sm",
          )}
        >
          {period.label}
        </button>
      ))}
    </div>
  );
}

function RankingTable({ entries, me }: { entries: RankingEntry[]; me: MyRanking }) {
  if (entries.length === 0) {
    return <p className="text-sm text-muted-foreground">この期間に解いた参加者はまだいません。</p>;
  }

  // 表の外にいるときだけ、自分の順位を下に出す
  const meOutside = me.entry && !entries.some((entry) => entry.isMe) ? me.entry : null;

  return (
    <div className="space-y-2">
      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-14 text-right">順位</TableHead>
              {/* 残りの幅を名前に使う。セルの max-w-0 と組み合わせ、長い名前は列の中で折り返す */}
              <TableHead className="w-full">名前</TableHead>
              <TableHead className="text-right">正解</TableHead>
              <TableHead className="hidden text-right sm:table-cell">正答率</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {entries.map((entry) => (
              <EntryRow key={`${entry.rank}-${entry.name}`} entry={entry} />
            ))}
          </TableBody>
        </Table>
      </div>
      {meOutside && (
        <p className="text-sm text-muted-foreground">
          あなたは {meOutside.rank} 位（正解 {meOutside.correctCount} 問）です。
        </p>
      )}
    </div>
  );
}

function EntryRow({ entry }: { entry: RankingEntry }) {
  const rate = accuracy(entry.correctCount, entry.answeredCount);
  return (
    <TableRow className={cn(entry.isMe && "bg-primary/5")}>
      <TableCell className="text-right font-semibold tabular-nums">{entry.rank}</TableCell>
      <TableCell className="max-w-0 whitespace-normal">
        <span className="break-words">
          {entry.name}
          {entry.isMe && <span className="ml-1 text-xs whitespace-nowrap text-muted-foreground">（あなた）</span>}
        </span>
        {/* 狭い幅では正答率の列を畳み、名前の下に出す */}
        <span className="block text-xs text-muted-foreground sm:hidden">
          {entry.answeredCount} 問中・正答率 {rate}%
        </span>
      </TableCell>
      <TableCell className="text-right tabular-nums">{entry.correctCount}</TableCell>
      <TableCell className="hidden text-right text-muted-foreground tabular-nums sm:table-cell">
        {rate}%<span className="text-xs">（{entry.answeredCount} 問中）</span>
      </TableCell>
    </TableRow>
  );
}

/**
 * 参加の状態と操作。参加すると、決めた名前と成績がこのテナントの人に見える。
 * メールアドレスや、ほかで使っている名前は出ない。
 */
function ParticipationCard({ slug, me }: { slug: string; me: MyRanking }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  // 期間ごとのキャッシュをまとめて作り直す。キーの先頭（URL）だけで一致させる
  const refresh = () => queryClient.invalidateQueries({ queryKey: getGetRankingQueryKey(slug) });
  const leave = useLeaveRanking({ mutation: { onSuccess: refresh } });

  if (me.name == null || editing) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{me.name == null ? "ランキングに参加する" : "名前を変える"}</CardTitle>
          <CardDescription>
            参加すると、ここで決めた名前と成績が、このテナントの人に表示されます。
            メールアドレスは表示されません。いつでもやめられます。
          </CardDescription>
        </CardHeader>
        <CardContent>
          <NameForm
            slug={slug}
            current={me.name ?? ""}
            submitLabel={me.name == null ? "参加する" : "変える"}
            onDone={async () => {
              setEditing(false);
              await refresh();
            }}
            onCancel={me.name == null ? undefined : () => setEditing(false)}
          />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>「{me.name}」で参加しています</CardTitle>
        <CardDescription>
          {me.entry
            ? `この期間は ${me.entry.rank} 位です。`
            : "この期間はまだ解いていないため、ランキングには出ていません。"}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {leave.isError && <ApiErrorAlert error={leave.error} />}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => setEditing(true)}>
            名前を変える
          </Button>
          <Button variant="ghost" disabled={leave.isPending} onClick={() => leave.mutate({ slug })}>
            参加をやめる
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function NameForm({
  slug,
  current,
  submitLabel,
  onDone,
  onCancel,
}: {
  slug: string;
  current: string;
  submitLabel: string;
  onDone: () => Promise<void>;
  onCancel?: () => void;
}) {
  const form = useForm<ParticipationFormInput, unknown, ParticipationFormValues>({
    resolver: zodResolver(ParticipationFormSchema),
    defaultValues: { name: current },
  });
  const { errors } = form.formState;
  const participate = useParticipateInRanking();

  const submit = form.handleSubmit(async (values) => {
    // 失敗（同じ名前がある、など）は participate.error として画面に出る
    const done = await participate.mutateAsync({ slug, data: values }).catch(() => null);
    if (done) await onDone();
  });

  return (
    <form onSubmit={submit} className="space-y-3">
      <Field data-invalid={!!errors.name}>
        <FieldLabel htmlFor="ranking-name">ランキングに出す名前</FieldLabel>
        <Input id="ranking-name" autoComplete="nickname" aria-invalid={!!errors.name} {...form.register("name")} />
        <FieldError errors={[errors.name]} />
      </Field>
      {participate.isError && <ApiErrorAlert error={participate.error} />}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            やめる
          </Button>
        )}
      </div>
    </form>
  );
}
