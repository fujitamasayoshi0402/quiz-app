import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { ApiError } from "@/lib/api/fetcher";
import { getGetCurrentAttemptQueryKey, resumeAttempt } from "@/lib/api/generated/endpoints";

/**
 * 回答が 409 になったとき、挑戦が別の画面で終わったのかを確かめる（DEV-204。web の `useEndedElsewhere`）。
 *
 * 409 は「挑戦が終わっている」と「出題後にクイズが消えた」の両方で返る。
 * **エラーの文言では分けない**（文言が変わると黙って壊れる）。挑戦を読み直し、状態で分ける。
 * 中断中の挑戦は 1 人 1 つのため、web で破棄して始め直すと、アプリの挑戦が終わる。
 *
 * 確かめている間（[checking]）は、クイズを飛ばすボタンを出さない。先に押されると、終わった挑戦を最後まで進めてしまう。
 */
export function useEndedElsewhere(slug: string, attemptId: string) {
  const queryClient = useQueryClient();
  const [checking, setChecking] = useState(false);
  const [ended, setEnded] = useState(false);

  const check = useCallback(
    async (error: unknown) => {
      if (!(error instanceof ApiError && error.status === 409)) return;
      setChecking(true);
      try {
        const latest = await resumeAttempt(slug, attemptId);
        if (latest.status !== "in_progress") {
          // 出題の画面に、終わった挑戦を「中断中」として残さない
          queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
          setEnded(true);
        }
      } catch {
        // 読み直せなければ、いままでどおり飛ばせるようにする
      } finally {
        setChecking(false);
      }
    },
    [queryClient, slug, attemptId],
  );

  return { ended, checking, check };
}
