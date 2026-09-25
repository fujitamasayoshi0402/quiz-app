import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ApiError } from "@/lib/api/fetcher";

/**
 * API のエラーを利用者に伝える。
 *
 * - 401 … 利用者の選び直しへ誘導する
 * - 400 の入力エラー … バックエンドが項目ごとの理由（`errors`）を返すので、それを並べる
 * - 理由のないエラー … 通信の失敗や、バックエンドに届く前に返った応答。時間をおいて試すよう伝える。
 *   AWS では、夜間にバックエンドを止めている間、ALB が本文のない 503 を返す
 */
export function ApiErrorAlert({ error }: { error: unknown }) {
  const apiError = error instanceof ApiError ? error : null;
  const fieldErrors = fieldErrorsOf(apiError);
  return (
    <Alert variant="destructive">
      <AlertCircle />
      <AlertTitle>{apiError?.problem?.title ?? "エラーが発生しました"}</AlertTitle>
      <AlertDescription>
        {apiError?.problem?.detail && <p>{apiError.problem.detail}</p>}
        {fieldErrors.length > 0 && (
          <ul className="list-disc pl-4">
            {fieldErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        )}
        {!apiError?.problem && (
          <p>
            {apiError?.status === 503 && "サーバーが止まっているか、起動の途中です。"}
            時間をおいてもう一度お試しください
          </p>
        )}
        {apiError?.status === 401 && (
          <Link href="/login" className="underline underline-offset-4">
            利用者を選ぶ
          </Link>
        )}
      </AlertDescription>
    </Alert>
  );
}

function fieldErrorsOf(error: ApiError | null): string[] {
  const errors = error?.problem?.errors;
  if (!errors || typeof errors !== "object") return [];
  return Object.values(errors).filter((message): message is string => typeof message === "string");
}
