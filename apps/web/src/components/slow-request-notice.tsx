"use client";

import { useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { type SlowRequestState, watchSlowRequests } from "@/lib/slow-requests";

/**
 * 読み込みや保存が数秒を超えたら、画面の下に案内を出す（DEV-90）。
 *
 * dev の Aurora は、使っていない間は一時停止している。止まっている DB への最初の要求は 15〜20 秒かかり、
 * その間スケルトンのまま止まって見える。壊れたのではなく待てばよいことを伝える。
 *
 * 遅い理由が DB の起動だとは、画面からは分からない。そのため「起動している」とは言い切らない。
 * 押せるものは置かず、下にあるボタンの操作も妨げない。
 */
export function SlowRequestNotice() {
  const client = useQueryClient();
  const [state, setState] = useState<SlowRequestState>("none");
  useEffect(() => watchSlowRequests(client, setState), [client]);

  // 読み上げの領域は常に置いておき、中身だけを替える。案内と一緒に現れた領域は、読み上げられないことがある
  return (
    <div
      role="status"
      className="pointer-events-none fixed inset-x-0 bottom-0 z-[100] flex justify-center px-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
    >
      {state !== "none" && (
        <div className="bg-background flex max-w-sm items-start gap-3 rounded-lg border px-4 py-3 text-sm shadow-lg">
          <LoaderCircle className="text-muted-foreground mt-0.5 size-4 shrink-0 animate-spin" aria-hidden />
          <div className="grid gap-0.5">
            <p className="font-medium">
              {state === "retrying" ? "応答がなかったため、もう一度試しています" : "サーバーの応答を待っています"}
            </p>
            <p className="text-muted-foreground">
              しばらく使われていなかったときは、データベースの起動に 20 秒ほどかかります
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
