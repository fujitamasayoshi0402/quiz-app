import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ApiError } from "@/lib/api/fetcher";

/** API のエラーを利用者に伝える。401 のときは利用者の選び直しへ誘導する。 */
export function ApiErrorAlert({ error }: { error: unknown }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <Alert variant="destructive">
      <AlertCircle />
      <AlertTitle>{apiError?.problem?.title ?? "エラーが発生しました"}</AlertTitle>
      <AlertDescription>
        <p>{apiError?.problem?.detail ?? "時間をおいてもう一度お試しください"}</p>
        {apiError?.status === 401 && (
          <Link href="/" className="underline underline-offset-4">
            利用者を選ぶ
          </Link>
        )}
      </AlertDescription>
    </Alert>
  );
}
