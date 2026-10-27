"use client";

import { useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { WAKE_REQUESTED_KEY, type WakeResult, onWakeStarted, resumeWake, wakeBackend } from "@/lib/wake";

/**
 * 止まっているサーバーを起こすボタン（DEV-154）。止まっている旨の案内（`ApiErrorAlert`）の中に出す。
 *
 * 使えるようになったら、読み込みをやり直す。保存などの操作は、利用者がもう一度押す。
 * 起動を頼んだあとは、読み込み直しても、別のタブでも、同じ画面のほかの案内でも、ボタンの代わりに起動中を出す（DEV-211）
 */
export function WakeServerButton() {
  const client = useQueryClient();
  const [state, setState] = useState<"idle" | "waking" | WakeResult>("idle");

  useEffect(() => {
    let active = true;
    const onReady = () => client.invalidateQueries();
    const follow = (wake: Promise<WakeResult> | null) => {
      if (!wake) return;
      setState("waking");
      void wake.then((result) => {
        if (active) setState(result);
      });
    };
    // 別のタブで頼んだら、こちらは起こさずに続きを待つ
    const onStorage = (event: StorageEvent) => {
      if (event.key === WAKE_REQUESTED_KEY && event.newValue) follow(resumeWake({ onReady }));
    };

    const unsubscribe = onWakeStarted(follow);
    window.addEventListener("storage", onStorage);
    follow(resumeWake({ onReady }));
    return () => {
      active = false;
      unsubscribe();
      window.removeEventListener("storage", onStorage);
    };
  }, [client]);

  // 押したら、onWakeStarted を通して、このボタンも同じ画面のほかの案内も起動中になる
  function wake() {
    void wakeBackend({ onReady: () => client.invalidateQueries() });
  }

  // 読み上げの領域は常に置いておき、中身だけを替える
  return (
    <div className="mt-2 grid gap-2">
      {(state === "idle" || state === "failed") && (
        <Button type="button" variant="outline" size="sm" className="w-fit" onClick={wake}>
          サーバーを起動する
        </Button>
      )}
      <p role="status" className="text-muted-foreground">
        {state === "idle" && "使われていないと止まります。起動には 2〜3 分かかります"}
        {state === "waking" && (
          <span className="inline-flex items-center gap-2">
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
            起動しています。使えるようになったら、自動で読み込み直します
          </span>
        )}
        {state === "ready" && "使えるようになりました。保存などの操作は、もう一度お試しください"}
        {state === "failed" && "起動できませんでした。時間をおいてもう一度お試しください"}
        {state === "timeout" && "起動に時間がかかっています。少し待ってから、ページを読み込み直してください"}
      </p>
    </div>
  );
}
