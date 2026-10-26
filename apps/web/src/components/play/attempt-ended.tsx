import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Panel } from "@/components/play/parts";

/** 別の画面で挑戦が終わったとき（DEV-204）。解き続けても結果がないため、出題の画面へ戻る口だけを出す */
export const ENDED_ELSEWHERE = "このクイズは別の画面で終了しました";

/** 破棄した挑戦には結果がない。完了 API は 409 を返す */
export const ABANDONED = "このクイズは破棄されたため、結果はありません";

export function AttemptEnded({ slug, message }: { slug: string; message: string }) {
  return (
    <Panel className="space-y-4">
      <p className="font-semibold">{message}</p>
      <Button asChild className="rounded-full">
        <Link href={`/t/${slug}/play`}>クイズを選ぶ画面に戻る</Link>
      </Button>
    </Panel>
  );
}
