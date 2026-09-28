"use client";

import { useEffect, useRef } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { useCreateFigure } from "@/lib/api/generated/endpoints";
import { svgFromDataUri } from "@/lib/figures";

/** draw.io の埋め込み。図のデータはブラウザの中で扱われ、draw.io のサーバーには送られない */
const DRAWIO_ORIGIN = "https://embed.diagrams.net";
const DRAWIO_URL = `${DRAWIO_ORIGIN}/?${new URLSearchParams({
  embed: "1",
  // やり取りを JSON の文字列にする
  proto: "json",
  spin: "1",
  lang: "ja",
  // 保存は「保存して閉じる」だけにする（saveAndExit、noSaveBtn）。保存するたびに新しい図ができるため、途中の保存を作らない
  saveAndExit: "1",
  noSaveBtn: "1",
  libraries: "1",
})}`;

type DrawioMessage =
  | { event: "init" }
  | { event: "save"; xml: string }
  | { event: "export"; data: string; xml?: string }
  | { event: "exit" };

/**
 * draw.io を開き、描いた図を解説図として置く（ADR-0017、ADR-0020）。
 *
 * **受け取るメッセージは、送り元を確かめる。** 埋め込んだ draw.io の window からのものだけを扱う。
 * ほかのタブや別の iframe が送ってきたメッセージで、図を置かせない。
 *
 * 図は書き換えない。描き直しても新しい ID の図として置き、[onSaved] で ID を返す。
 */
export function FigureEditor({
  slug,
  source,
  open,
  onOpenChange,
  onSaved,
}: {
  slug: string;
  /** 描き直すときの原本。新しく描くときは無し */
  source?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved: (id: string) => void;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  // 保存を押されたときの原本。書き出しの応答に原本が付いてこなかったときに使う
  const savedXml = useRef("");
  const create = useCreateFigure();
  const { mutate, reset, isPending } = create;

  // 開き直したら、前に置けなかったときのエラーを消す
  useEffect(() => {
    if (open) reset();
  }, [open, reset]);

  useEffect(() => {
    if (!open) return;
    const post = (message: object) => frame.current?.contentWindow?.postMessage(JSON.stringify(message), DRAWIO_ORIGIN);

    const onMessage = (event: MessageEvent) => {
      if (event.origin !== DRAWIO_ORIGIN || event.source !== frame.current?.contentWindow) return;
      const message = parse(event.data);
      switch (message?.event) {
        case "init":
          post({ action: "load", xml: source ?? "", autosave: 0 });
          break;
        // 保存を押されたら、SVG に書き出させる。原本と SVG がそろってから置く
        case "save":
          savedXml.current = message.xml;
          post({ action: "export", format: "svg", xml: message.xml, spinKey: "saving" });
          break;
        case "export":
          mutate(
            { slug, data: { source: message.xml ?? savedXml.current, svg: svgFromDataUri(message.data) } },
            {
              onSuccess: ({ id }) => {
                onSaved(id);
                onOpenChange(false);
              },
              // 置けなかったときは draw.io を開いたままにし、描いたものを失わせない
              onError: () => post({ action: "spinner", show: false }),
            },
          );
          break;
        case "exit":
          onOpenChange(false);
          break;
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [open, source, slug, mutate, onSaved, onOpenChange]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        // 描いている途中で、Esc で閉じて描いたものを失わせない。閉じるのは draw.io の「終了」か、上の「閉じる」
        onEscapeKeyDown={(event) => event.preventDefault()}
        className="top-0 left-0 flex h-dvh w-full max-w-none translate-x-0 translate-y-0 flex-col gap-0 rounded-none p-0 sm:max-w-none"
      >
        <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2">
          <div className="mr-auto">
            <DialogTitle>{source ? "図を描き直す" : "図を描く"}</DialogTitle>
            <DialogDescription>
              描き終えたら「保存して閉じる」を押します。描き直した図は新しい図として置かれ、解説の参照が差し替わります。
            </DialogDescription>
          </div>
          {isPending && <span className="text-muted-foreground text-sm">保存しています…</span>}
          <Button type="button" variant="outline" size="sm" onClick={() => onOpenChange(false)}>
            閉じる
          </Button>
        </div>
        {create.error && (
          <div className="px-4 py-2">
            <ApiErrorAlert error={create.error} />
          </div>
        )}
        {open && <iframe ref={frame} src={DRAWIO_URL} title="draw.io" className="min-h-0 w-full flex-1 border-0" />}
      </DialogContent>
    </Dialog>
  );
}

function parse(data: unknown): DrawioMessage | undefined {
  if (typeof data !== "string") return undefined;
  try {
    return JSON.parse(data) as DrawioMessage;
  } catch {
    return undefined;
  }
}
