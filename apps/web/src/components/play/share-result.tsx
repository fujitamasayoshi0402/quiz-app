"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Download, Share2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Panel } from "@/components/play/parts";
import { ApiError, apiFetch } from "@/lib/api/fetcher";

/**
 * 結果を共有する（DEV-207）。押すと、web のサーバーが共有のリンクを作る（`app/share/route.ts`）。
 * リンクのほかに、カードの画像（`/share/<トークン>/opengraph-image`）も、画像のまま共有・保存できる。
 *
 * **作ってから、もう一度押してもらう。** 共有のシート（Web Share API）とクリップボードは、押した直後にしか使えないブラウザがある。
 * リンクを作る通信や、画像を取る通信を待つと、その間に押した扱いが切れる（Safari）。画像も、リンクを作ったら先に取っておく
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
  const url = create.data?.url;

  if (url) {
    return (
      <Panel className="space-y-5">
        <CardImage url={url} />
        <ShareLink url={url} />
        <p className="text-xs text-muted-foreground">
          画像とリンクには、カテゴリの名前と正答率だけが出ます。あなたの名前やテナントは出ません。
        </p>
      </Panel>
    );
  }

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
        {create.isPending ? "準備しています…" : "結果を共有する"}
      </Button>
    </>
  );
}

const IMAGE_NAME = "quiz-result.png";

/** カードの画像。SNS に画像として載せたり、端末に保存したりする */
function CardImage({ url }: { url: string }) {
  const image = useQuery({
    queryKey: ["share-card-image", url],
    queryFn: async ({ signal }) => {
      const response = await fetch(`${new URL(url).pathname}/opengraph-image`, { signal });
      if (!response.ok) throw new ApiError(response.status, null);
      const file = new File([await response.blob()], IMAGE_NAME, { type: "image/png" });
      return { file, preview: await dataUrlOf(file) };
    },
    staleTime: Infinity,
  });

  if (image.isError) return <ApiErrorAlert error={image.error} />;
  if (!image.data) return <Skeleton className="aspect-[1200/630] w-full rounded-xl" />;

  const { file, preview } = image.data;
  const canShareFile = typeof navigator.canShare === "function" && navigator.canShare({ files: [file] });

  return (
    <div className="space-y-3">
      <p className="text-sm font-semibold">画像</p>
      {/* eslint-disable-next-line @next/next/no-img-element -- 手元の画像（data:）で、最適化するものがない */}
      <img src={preview} alt="結果のカード（正答率と正解の数）" className="w-full rounded-xl border" />
      <div className="flex flex-wrap gap-2">
        {canShareFile && (
          <Button
            className="rounded-full"
            onClick={() => void navigator.share({ files: [file] }).catch(() => undefined)}
          >
            <Share2 />
            画像で共有
          </Button>
        )}
        <Button variant="outline" className="rounded-full" asChild>
          <a href={preview} download={IMAGE_NAME}>
            <Download />
            画像を保存
          </a>
        </Button>
      </div>
    </div>
  );
}

function ShareLink({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);

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
    <div className="space-y-3">
      <p className="text-sm font-semibold">リンク</p>
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
            リンクで共有
          </Button>
        )}
        <Button variant="outline" className="rounded-full" onClick={() => void copy()}>
          {copied ? "コピーしました" : "リンクをコピー"}
        </Button>
      </div>
    </div>
  );
}

/**
 * プレビューと保存に使う data: の URL。blob: の URL と違い、手放す手間がない。
 * blob: を画面を離れるときに手放すと、開発のときの React の二度の実行（StrictMode）で、表示中の画像が消える
 */
function dataUrlOf(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error ?? new Error("画像を読めませんでした"));
    reader.readAsDataURL(file);
  });
}
