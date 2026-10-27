import { cardSize, renderCardImage } from "@/lib/share/card-image";

/** 共有した結果のカード（DEV-207。`lib/share/card-image.tsx`） */
export const size = cardSize;
export const contentType = "image/png";
export const alt = "クイズの結果（正答率と正解の数）";

export default async function Image({ params }: { params: Promise<{ token: string }> }) {
  return renderCardImage((await params).token);
}
