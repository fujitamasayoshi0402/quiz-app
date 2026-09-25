"use client";

import Link from "next/link";
import { useState } from "react";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useImportQuizzes } from "@/lib/api/generated/endpoints";
import { ApiError } from "@/lib/api/fetcher";
import { useInvalidateTenant } from "@/lib/admin/invalidate";
import {
  CSV_COLUMNS,
  MAX_IMPORT_ROWS,
  type ParsedImport,
  SAMPLE_CSV,
  SAMPLE_JSON,
  decodeImportFile,
  parseImportFile,
  rejectedRowsOf,
} from "@/lib/admin/quiz-import";

type Loaded = { fileName: string; parsed: ParsedImport };

/**
 * クイズの一括取り込み。
 *
 * ファイルを選ぶと、その場で読んで件数と中身を見せる。取り込むのはボタンを押してから。
 * **1 件でも取り込めない行があれば、何も入らない。** 取り込めない行は、ファイルの中の位置（CSV なら行番号）で示す。
 */
export function QuizImport({ slug }: { slug: string }) {
  const invalidate = useInvalidateTenant(slug);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [imported, setImported] = useState<number | null>(null);
  // 取り込んだら、ファイルの欄を空に戻す。残すと、同じファイルを選び直しても変更として扱われない
  const [inputKey, setInputKey] = useState(0);
  const importQuizzes = useImportQuizzes({
    mutation: {
      onSuccess: async (response) => {
        await invalidate();
        setImported(response.importedCount);
        setLoaded(null);
        setInputKey((key) => key + 1);
      },
    },
  });

  const selectFile = async (file: File | undefined) => {
    importQuizzes.reset();
    setImported(null);
    if (!file) {
      setLoaded(null);
      return;
    }
    const text = decodeImportFile(await file.arrayBuffer());
    setLoaded({ fileName: file.name, parsed: parseImportFile(file.name, text) });
  };

  const parsed = loaded?.parsed;
  const fileName = loaded?.fileName;
  const rejected =
    parsed?.ok && importQuizzes.error instanceof ApiError
      ? rejectedRowsOf(importQuizzes.error.problem?.rows, parsed.labels)
      : null;

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="space-y-3">
          <Input
            key={inputKey}
            type="file"
            accept=".csv,.json,text/csv,application/json"
            aria-label="取り込むファイル"
            onChange={(event) => void selectFile(event.target.files?.[0])}
          />
          <p className="text-muted-foreground text-sm">
            CSV か JSON のファイルを選んでください。1 回に {MAX_IMPORT_ROWS} 件まで取り込めます。
            カテゴリと難易度は名前で指定し、先に作っておく必要があります。
          </p>
          <FormatHelp />
        </CardContent>
      </Card>

      {imported !== null && (
        <Alert>
          <CheckCircle2 />
          <AlertTitle>{imported} 件を取り込みました</AlertTitle>
          <AlertDescription>
            <Link href={`/t/${slug}/admin/quizzes`} className="underline underline-offset-4">
              クイズの一覧を見る
            </Link>
          </AlertDescription>
        </Alert>
      )}

      {parsed && !parsed.ok && (
        <Alert variant="destructive">
          <AlertCircle />
          <AlertTitle>{fileName} を読めません</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {parsed.errors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {rejected ? (
        <Alert variant="destructive">
          <AlertCircle />
          <AlertTitle>取り込めない行があります。1 件も取り込んでいません</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {rejected.map((row) => (
                <li key={row.label}>
                  {row.label}: {row.messages.join("、")}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      ) : (
        importQuizzes.error && <ApiErrorAlert error={importQuizzes.error} />
      )}

      {parsed?.ok && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm">
              {fileName} から {parsed.rows.length} 件を読み取りました。
            </p>
            <Button
              className="ml-auto"
              disabled={importQuizzes.isPending}
              onClick={() => importQuizzes.mutate({ slug, data: { quizzes: parsed.rows } })}
            >
              {importQuizzes.isPending ? "取り込んでいます…" : `${parsed.rows.length} 件を取り込む`}
            </Button>
          </div>
          <div className="bg-background rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-20">位置</TableHead>
                  <TableHead>問題文</TableHead>
                  <TableHead className="hidden w-40 sm:table-cell">カテゴリ・難易度</TableHead>
                  <TableHead className="w-20">状態</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {parsed.rows.map((row, index) => (
                  <TableRow key={parsed.labels[index]}>
                    <TableCell className="text-muted-foreground text-xs">{parsed.labels[index]}</TableCell>
                    <TableCell className="max-w-0">
                      <p className="line-clamp-2 whitespace-normal">{row.question}</p>
                      <p className="text-muted-foreground mt-0.5 truncate text-xs sm:hidden">
                        {row.category}・{row.difficulty}
                      </p>
                    </TableCell>
                    <TableCell className="hidden truncate sm:table-cell">
                      {row.category}・{row.difficulty}
                    </TableCell>
                    <TableCell className="text-xs">{row.status === "published" ? "公開" : "下書き"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      )}
    </div>
  );
}

/** 形式の説明とサンプル。サンプルはその場で作ってダウンロードさせる */
function FormatHelp() {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer">ファイルの形式</summary>
      <div className="text-muted-foreground mt-2 space-y-2">
        <p>
          <strong>CSV</strong>: 1 行目に列名を並べます（{CSV_COLUMNS.join(", ")}）。正解は correct に選択肢の番号（1〜4）で書きます。
          status は draft（下書き）か published（公開）で、空なら下書きです。解説に改行やカンマを含めるときは、値を &quot; で囲みます。
          Excel で作るときは「CSV UTF-8」で保存してください。
        </p>
        <p>
          <strong>JSON</strong>: クイズの配列です。各クイズに category, difficulty, question, choices（body と isCorrect の配列）,
          explanation, status を書きます。
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" onClick={() => download("quiz-import-sample.csv", SAMPLE_CSV, "text/csv")}>
            CSV のサンプル
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => download("quiz-import-sample.json", SAMPLE_JSON, "application/json")}
          >
            JSON のサンプル
          </Button>
        </div>
      </div>
    </details>
  );
}

function download(fileName: string, content: string, type: string) {
  // Excel が UTF-8 と判別できるよう、CSV には BOM を付ける
  const body = type === "text/csv" ? `﻿${content}` : content;
  const url = URL.createObjectURL(new Blob([body], { type: `${type};charset=utf-8` }));
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}
