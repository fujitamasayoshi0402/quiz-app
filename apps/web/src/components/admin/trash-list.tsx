"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  useListTrash,
  useRestoreCategory,
  useRestoreDifficulty,
  useRestoreQuiz,
} from "@/lib/api/generated/endpoints";
import type { DeletedItem } from "@/lib/api/generated/model";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * 削除済み一覧。ここから復活させる（要件定義 A7）。
 *
 * カテゴリを復活させると、一緒に削除された難易度とクイズも戻る。
 * 親が削除済みの項目は単独では戻せないので、先に親を戻すよう案内する。
 */
export function TrashList({ slug }: { slug: string }) {
  const trash = useListTrash(slug);
  const invalidate = useInvalidateTenant(slug);
  const onSuccess = () => invalidate();

  const restoreCategory = useRestoreCategory({ mutation: { onSuccess } });
  const restoreDifficulty = useRestoreDifficulty({ mutation: { onSuccess } });
  const restoreQuiz = useRestoreQuiz({ mutation: { onSuccess } });
  const error = restoreCategory.error ?? restoreDifficulty.error ?? restoreQuiz.error;
  const pending = restoreCategory.isPending || restoreDifficulty.isPending || restoreQuiz.isPending;

  if (trash.isPending) return <Skeleton className="h-64 w-full" />;
  if (trash.isError) return <ApiErrorAlert error={trash.error} />;

  const { categories = [], difficulties = [], quizzes = [] } = trash.data;
  if (categories.length + difficulties.length + quizzes.length === 0) {
    return <p className="text-muted-foreground text-sm">削除済みの項目はありません。</p>;
  }

  return (
    <div className="space-y-6">
      {error && <ApiErrorAlert error={error} />}
      <Section
        title="カテゴリ"
        items={categories}
        pending={pending}
        onRestore={(id) => restoreCategory.mutate({ slug, id })}
      />
      <Section
        title="難易度"
        items={difficulties}
        pending={pending}
        onRestore={(id) => restoreDifficulty.mutate({ slug, id })}
      />
      <Section title="クイズ" items={quizzes} pending={pending} onRestore={(id) => restoreQuiz.mutate({ slug, id })} />
    </div>
  );
}

function Section({
  title,
  items,
  pending,
  onRestore,
}: {
  title: string;
  items: DeletedItem[];
  pending: boolean;
  onRestore: (id: string) => void;
}) {
  if (items.length === 0) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="divide-y">
          {items.map((item) => (
            <li key={item.id} className="flex items-center gap-3 py-2">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{item.name}</p>
                <p className="text-muted-foreground text-xs">
                  {item.categoryName && `${item.categoryName}・`}
                  {dateFormat.format(new Date(item.deletedAt))} に削除
                  {!item.restorable && "・親が削除済みのため、先に親を復活させてください"}
                </p>
              </div>
              <Button variant="outline" size="sm" disabled={!item.restorable || pending} onClick={() => onRestore(item.id)}>
                復活させる
              </Button>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
