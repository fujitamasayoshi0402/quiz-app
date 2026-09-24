"use client";

import Link from "next/link";
import { useState } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useSearchQuizzes } from "@/lib/api/generated/endpoints";
import { useCatalog } from "@/lib/admin/catalog";

/** 「すべて」を表す値。Select は空文字列を値にできない */
const ALL = "all";

/** クイズ一覧。カテゴリ・難易度・状態で絞り込む（要件定義 A4）。 */
export function QuizList({ slug }: { slug: string }) {
  const catalog = useCatalog(slug);
  const [categoryId, setCategoryId] = useState(ALL);
  const [difficultyId, setDifficultyId] = useState(ALL);
  const [status, setStatus] = useState(ALL);

  const quizzes = useSearchQuizzes(slug, {
    categoryId: categoryId === ALL ? undefined : categoryId,
    difficultyId: difficultyId === ALL ? undefined : difficultyId,
    status: status === ALL ? undefined : status,
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Select
          value={categoryId}
          onValueChange={(value) => {
            setCategoryId(value);
            // 難易度はカテゴリ配下なので、カテゴリを変えたら選び直させる
            setDifficultyId(ALL);
          }}
        >
          <SelectTrigger className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>すべてのカテゴリ</SelectItem>
            {catalog.categories.data?.map((category) => (
              <SelectItem key={category.id} value={category.id}>
                {category.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={difficultyId} onValueChange={setDifficultyId} disabled={categoryId === ALL}>
          <SelectTrigger className="w-36">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>すべての難易度</SelectItem>
            {catalog.difficultiesOf(categoryId === ALL ? undefined : categoryId).map((difficulty) => (
              <SelectItem key={difficulty.id} value={difficulty.id}>
                {difficulty.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger className="w-32">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>すべての状態</SelectItem>
            <SelectItem value="published">公開</SelectItem>
            <SelectItem value="draft">下書き</SelectItem>
          </SelectContent>
        </Select>
        <Button asChild className="ml-auto">
          <Link href={`/t/${slug}/admin/quizzes/new`}>クイズを作る</Link>
        </Button>
      </div>

      {quizzes.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : quizzes.isError ? (
        <ApiErrorAlert error={quizzes.error} />
      ) : quizzes.data.length === 0 ? (
        <p className="text-muted-foreground py-8 text-center text-sm">該当するクイズはありません。</p>
      ) : (
        <div className="bg-background rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>問題文</TableHead>
                <TableHead className="w-32">カテゴリ</TableHead>
                <TableHead className="w-24">難易度</TableHead>
                <TableHead className="w-20">状態</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {quizzes.data.map((quiz) => (
                <TableRow key={quiz.id}>
                  <TableCell className="max-w-0">
                    <Link
                      href={`/t/${slug}/admin/quizzes/${quiz.id}`}
                      className="block truncate underline-offset-4 hover:underline"
                    >
                      {quiz.question}
                    </Link>
                  </TableCell>
                  <TableCell>{catalog.categoryName(quiz.categoryId)}</TableCell>
                  <TableCell>{catalog.difficultyName(quiz.difficultyId)}</TableCell>
                  <TableCell>
                    <StatusBadge status={quiz.status} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
