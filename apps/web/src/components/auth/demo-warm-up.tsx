"use client";

import { useEffect } from "react";

/**
 * 「デモとして入る」の入口を表示したら、止まっている DB を起こし始めるよう web のサーバーに頼む（DEV-195。`/auth/demo/warm`）。
 *
 * 入口を読んでいる間に DB の復帰が進み、押したあとの待ちが短くなる。結果は待たず、失敗しても何もしない。
 * 画面に出すものはない
 */
export function DemoWarmUp() {
  useEffect(() => {
    fetch("/auth/demo/warm", { method: "POST" }).catch(() => undefined);
  }, []);
  return null;
}
