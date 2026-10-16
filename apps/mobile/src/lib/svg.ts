/**
 * 解説図の SVG を、アプリの中で描くための部品（ADR-0032）。
 *
 * React Native には SVG を描く部品がない。draw.io の SVG は、HTML のラベルを `foreignObject` で持つため、
 * SVG を自前で描くライブラリでは崩れる。そこで WebView の中で、**web と同じく `<img>` として**描く。
 * `<img>` として読んだ SVG は、スクリプトを動かさず、外のものも読み込まない。そのうえで WebView の JavaScript を切り、
 * 配信のときと同じ CSP（`modules/figures` の CloudFront）を付ける
 */

const CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:";

/** SVG を `<img>` として描くだけの文書。WebView に渡す */
export function svgDocument(svg: string): string {
  const src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  return [
    "<!doctype html>",
    `<meta http-equiv="Content-Security-Policy" content="${CSP}">`,
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    "<style>html,body{margin:0;background:#fff}img{display:block;width:100%;height:auto}</style>",
    `<img src="${src}" alt="">`,
  ].join("");
}

/**
 * SVG の縦横の比（幅 / 高さ）。WebView は中身の高さに合わせて伸びないため、描く前に高さを決める。
 * ルートの `viewBox` を先に見て、なければ `width` と `height` を見る。読めなければ null
 */
export function svgAspectRatio(svg: string): number | null {
  const root = svg.match(/<svg\b[^>]*>/i)?.[0];
  if (!root) return null;
  const viewBox = attribute(root, "viewBox")
    ?.trim()
    .split(/[\s,]+/)
    .map(Number);
  if (viewBox?.length === 4) {
    const [, , width, height] = viewBox;
    if (width > 0 && height > 0) return width / height;
  }
  const width = length(attribute(root, "width"));
  const height = length(attribute(root, "height"));
  return width && height ? width / height : null;
}

function attribute(tag: string, name: string): string | undefined {
  return tag
    .match(new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`, "i"))
    ?.slice(1)
    .find((v) => v !== undefined);
}

/** 単位のない数か px だけを読む。`100%` や `10em` は、比を決められない */
function length(value: string | undefined): number | null {
  const match = value?.trim().match(/^(\d+(?:\.\d+)?)(px)?$/);
  const number = match ? Number(match[1]) : 0;
  return number > 0 ? number : null;
}
