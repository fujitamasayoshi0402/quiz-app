"use client";

import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { Share2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Panel } from "@/components/play/parts";
import { apiFetch } from "@/lib/api/fetcher";

/**
 * 結果を共有する（DEV-207）。押すと、web のサーバーが共有のリンクを作る（`app/share/route.ts`）。
 *
 * **作ってから、もう一度押してもらう。** 共有のシート（Web Share API）とクリップボードは、押した直後にしか使えないブラウザがある。
 * リンクを作る通信を待つと、その間に押した扱いが切れる（Safari）
 */
export function ShareResult({ slug, attemptId }: { slug: string; attemptId: string }) {
  const create = useMutation({
    mutationFn: () =>
      apiFetch<{ url: string }>("/share", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ slug, attemptId }),
      }),
  });
  const [copied, setCopied] = useState(false);
  const url = create.data?.url;

  if (!url) {
    return (
      <>
        {create.error && <ApiErrorAlert error={create.error} />}
        <Button
          size="lg"
          variant="outline"
          className="w-full rounded-full"
          disabled={create.isPending}
          onClick={() => create.mutate()}
        >
          <Share2 />
          {create.isPending ? "リンクを作っています…" : "結果を共有する"}
        </Button>
      </>
    );
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
    } catch {
      // コピーできないブラウザでは、リンクを選んで自分でコピーしてもらう
      document.querySelector<HTMLInputElement>("[data-slot=share-url]")?.select();
    }
  };

  return (
    <Panel className="space-y-3">
      <p className="text-sm font-semibold">共有のリンク</p>
      <Input
        readOnly
        value={url}
        data-slot="share-url"
        aria-label="共有のリンク"
        onFocus={(event) => event.currentTarget.select()}
      />
      <div className="flex flex-wrap gap-2">
        {"share" in navigator && (
          <Button className="rounded-full" onClick={() => void navigator.share({ url }).catch(() => undefined)}>
            共有する
          </Button>
        )}
        <Button variant="outline" className="rounded-full" onClick={() => void copy()}>
          {copied ? "コピーしました" : "リンクをコピー"}
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        リンクを開いた人には、カテゴリの名前と正答率だけが見えます。あなたの名前やテナントは出ません。
      </p>
    </Panel>
  );
}
