"use client";

import Link from "next/link";
import { useState } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { StatusBadge } from "@/components/admin/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { accuracyOf, sortByAccuracy } from "@/lib/admin/accuracy";
import { useSearchQuizzes } from "@/lib/api/generated/endpoints";
import type { QuizListItemResponse } from "@/lib/api/generated/model";
import { useCatalog } from "@/lib/admin/catalog";

/** 「すべて」を表す値。Select は空文字列を値にできない */
const ALL = "all";

/** 作った人での絞り込み。AI が技術ニュースから作ったものは、出典の URL を持つ（ADR-0031） */
const ORIGINS = { ai: true, human: false } as const;

/** 正答率は、回答が少なければ率の代わりにそう出す。1 回まちがえただけで 0% と出さない */
function accuracyLabel(quiz: QuizListItemResponse): string {
  const accuracy = accuracyOf(quiz);
  return accuracy === null ? "回答が少ない" : `${accuracy}%`;
}

/**
 * クイズ一覧。カテゴリ・難易度・状態・作った人で絞り込む（要件定義 A4、A10）。
 * 正答率を出し、低い順に並べ替えられる。間違えやすい問題を見つけ、解説や問題文を見直すため（A11）。
 */
export function QuizList({ slug }: { slug: string }) {
  const catalog = useCatalog(slug);
  const [categoryId, setCategoryId] = useState(ALL);
  const [difficultyId, setDifficultyId] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [origin, setOrigin] = useState(ALL);
  const [order, setOrder] = useState("registered");

  const quizzes = useSearchQuizzes(slug, {
    categoryId: categoryId === ALL ? undefined : categoryId,
    difficultyId: difficultyId === ALL ? undefined : difficultyId,
    status: status === ALL ? undefined : status,
    aiGenerated: origin === ALL ? undefined : ORIGINS[origin as keyof typeof ORIGINS],
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
        <Select value={origin} onValueChange={setOrigin}>
          <SelectTrigger className="w-36" aria-label="作った人">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>すべての作成元</SelectItem>
            <SelectItem value="ai">AI が作ったもの</SelectItem>
            <SelectItem value="human">人が作ったもの</SelectItem>
          </SelectContent>
        </Select>
        <Select value={order} onValueChange={setOrder}>
          <SelectTrigger className="w-40" aria-label="並び">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="registered">登録順</SelectItem>
            <SelectItem value="accuracy">正答率の低い順</SelectItem>
          </SelectContent>
        </Select>
        <div className="ml-auto flex gap-2">
          <Button asChild variant="outline">
            <Link href={`/t/${slug}/admin/quizzes/import`}>まとめて取り込む</Link>
          </Button>
          <Button asChild>
            <Link href={`/t/${slug}/admin/quizzes/new`}>クイズを作る</Link>
          </Button>
        </div>
      </div>

      {quizzes.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : quizzes.isError ? (
        <ApiErrorAlert error={quizzes.error} />
      ) : quizzes.data.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">該当するクイズはありません。</p>
      ) : (
        <div className="rounded-lg border bg-background">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>問題文</TableHead>
                <TableHead className="hidden w-32 sm:table-cell">カテゴリ</TableHead>
                <TableHead className="hidden w-24 sm:table-cell">難易度</TableHead>
                <TableHead className="hidden w-28 text-right sm:table-cell">正答率</TableHead>
                <TableHead className="w-20">状態</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(order === "accuracy" ? sortByAccuracy(quizzes.data) : quizzes.data).map((quiz) => (
                <TableRow key={quiz.id}>
                  <TableCell className="max-w-0">
                    <Link
                      href={`/t/${slug}/admin/quizzes/${quiz.id}`}
                      className="line-clamp-2 whitespace-normal underline-offset-4 hover:underline sm:line-clamp-1"
                    >
                      {quiz.question}
                    </Link>
                    {/* 狭い幅ではカテゴリと難易度の列を畳み、問題文の下に出す。列のままだと問題文が数文字しか見えない */}
                    <p className="mt-0.5 truncate text-xs text-muted-foreground sm:hidden">
                      {catalog.categoryName(quiz.categoryId)}・{catalog.difficultyName(quiz.difficultyId)}・
                      {accuracyOf(quiz) === null ? accuracyLabel(quiz) : `正答率 ${accuracyLabel(quiz)}`}
                    </p>
                  </TableCell>
                  <TableCell className="hidden sm:table-cell">{catalog.categoryName(quiz.categoryId)}</TableCell>
                  <TableCell className="hidden sm:table-cell">{catalog.difficultyName(quiz.difficultyId)}</TableCell>
                  <TableCell className="hidden text-right sm:table-cell">
                    <span className={accuracyOf(quiz) === null ? "text-xs text-muted-foreground" : undefined}>
                      {accuracyLabel(quiz)}
                    </span>
                    <span className="block text-xs text-muted-foreground">{quiz.answerCount} 回答</span>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      <StatusBadge status={quiz.status} />
                      {quiz.sourceUrl && <Badge variant="outline">AI</Badge>}
                    </div>
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
