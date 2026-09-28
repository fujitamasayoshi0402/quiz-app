"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

/**
 * 解説図を出す（ADR-0020）。取れないとき（消された図、配信の失敗）は代わりの文字を出し、解説の文は崩さない。
 *
 * 背景は白にする。draw.io の図は背景が透明で、線と文字が黒い。暗い配色の画面でも読めるようにする。
 *
 * [zoomable] なら、図を押すと新しいタブで開く。狭い画面では縮んで細部が読めないため、開いた先で拡大して見る。
 */
export function FigureImage({
  src,
  alt,
  className,
  zoomable = false,
}: {
  src: string;
  alt: string;
  className?: string;
  zoomable?: boolean;
}) {
  const [failed, setFailed] = useState(false);

  if (failed) {
    return <span className="text-muted-foreground">{alt ? `（図を表示できません: ${alt}）` : "（図を表示できません）"}</span>;
  }
  const image = (
    // 図は API が署名付き URL へ送り、別のドメインから届く。next/image の最適化は通さない
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      className={cn("block h-auto max-w-full rounded-md border bg-white p-2", className)}
    />
  );
  if (!zoomable) return image;
  return (
    <a href={src} target="_blank" rel="noopener noreferrer" title="図を新しいタブで開く" className="block w-fit max-w-full">
      {image}
    </a>
  );
}
