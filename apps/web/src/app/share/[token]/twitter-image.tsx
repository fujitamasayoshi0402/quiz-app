import { cardSize, renderCardImage } from "@/lib/share/card-image";

/** X（Twitter）のカード。OGP と同じ画像にする（`opengraph-image.tsx`）。置かないと、ルートの既定のカードが出る */
export const size = cardSize;
export const contentType = "image/png";
export const alt = "クイズの結果（正答率と正解の数）";

export default async function Image({ params }: { params: Promise<{ token: string }> }) {
  return renderCardImage((await params).token);
}
