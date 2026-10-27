/**
 * 結果のカードの画像（`app/share/[token]/opengraph-image.tsx`）に使う、日本語のフォント（DEV-207）。**サーバーでだけ使う。**
 *
 * `ImageResponse` が持っているフォントには、日本語の字がない。Noto Sans JP の全体は数 MB あるため、リポジトリに置かず、
 * 描く文字だけを Google Fonts から取る（`text=`）。書体を指定しない要求には、`ImageResponse` が読める TrueType が返る。
 *
 * 取れなかったら undefined を返す。呼び出し元は、日本語を使わないカードに替える
 */
const TIMEOUT_MS = 5_000;

export type Font = { name: string; data: ArrayBuffer; weight: 400 | 700 };

export async function loadJapaneseFonts(text: string): Promise<Font[] | undefined> {
  try {
    const css = await fetch(
      `https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;700&text=${encodeURIComponent(text)}`,
      { signal: AbortSignal.timeout(TIMEOUT_MS) },
    );
    if (!css.ok) return undefined;
    const faces = [
      ...(await css.text()).matchAll(
        /font-weight: (400|700);[^}]*src: url\((.+?)\) format\('(?:opentype|truetype)'\)/g,
      ),
    ];
    if (faces.length === 0) return undefined;
    return await Promise.all(
      faces.map(async ([, weight, url]) => {
        const font = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
        if (!font.ok) throw new Error(`フォントを取れませんでした（${font.status}）`);
        return { name: "Noto Sans JP", data: await font.arrayBuffer(), weight: Number(weight) as 400 | 700 };
      }),
    );
  } catch (error) {
    console.warn("カードのフォントを取れませんでした", error instanceof Error ? error.name : error);
    return undefined;
  }
}
