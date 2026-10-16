import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api/fetcher";
import { getGetCurrentAttemptQueryKey, useStartAttempt } from "@/lib/api/generated/endpoints";
import type { StartAttemptRequest } from "@/lib/api/generated/model";
import { type FeedbackMode, rememberMode } from "@/lib/play/mode";

type Range = Pick<StartAttemptRequest, "categoryId" | "difficultyId" | "level">;

/**
 * 出題対象を決めた挑戦をすぐ始め、解く画面へ移る。並びはランダム。
 * 結果の画面と履歴の「間違えた問題を解き直す」（DEV-147、DEV-169）と、「今日の復習」（DEV-178）から使う。
 *
 * 別のタブで始めた挑戦が中断中だと 409 が返る（[conflict]）。黙って破棄せず、選ぶ画面で決めてもらう。
 * 案内の文言は、どこから押したかで変わるため、使う側が書く。
 */
export function useQuickStart(slug: string, mode: FeedbackMode, scope: string) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const start = useStartAttempt({
    mutation: {
      onSuccess: (attempt) => {
        rememberMode(attempt.id, mode);
        queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
        router.push(`/t/${slug}/play/session?attempt=${attempt.id}&mode=${mode}`);
      },
    },
  });

  return {
    start: (range: Range = {}) => start.mutate({ slug, data: { ...range, scope, order: "random" } }),
    isPending: start.isPending,
    conflict: start.error instanceof ApiError && start.error.status === 409,
    error: start.error,
  };
}

/** 範囲を決めて「間違えた問題」の挑戦をすぐ始める */
export function useRetryIncorrect(slug: string, mode: FeedbackMode) {
  const { start, ...rest } = useQuickStart(slug, mode, "incorrect");
  return { retry: start, ...rest };
}
