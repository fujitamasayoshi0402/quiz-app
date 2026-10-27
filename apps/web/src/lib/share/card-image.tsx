import { ImageResponse } from "next/og";
import { loadJapaneseFonts } from "@/lib/share/font";
import { openShareCard, rateOf, type ShareCard } from "@/lib/share/token";

/**
 * 共有した結果のカードの画像（DEV-207）。`app/share/[token]/` の `opengraph-image.tsx` と `twitter-image.tsx` が使う。
 *
 * 大きさは、ルートの既定のカード（`app/opengraph-image.png`）と同じ 1200×630。
 * 日本語のフォントが取れなければ、数字と英字だけのカードに替える。読めないトークンは、アプリの名前だけを出す
 */
export const cardSize = { width: 1200, height: 630 };

const colors = { background: "#f8fafc", text: "#0f172a", muted: "#64748b", accent: "#2563eb" };

export async function renderCardImage(token: string): Promise<ImageResponse> {
  const card = await openShareCard(token);
  if (!card) return new ImageResponse(<Fallback />, cardSize);

  const rate = `${rateOf(card)}%`;
  const score = `${card.total} 問中 ${card.correct} 問正解`;
  const tagline = "図解つきのクイズを作って解くアプリ";
  const fonts = await loadJapaneseFonts(`QUIZ RESULTQuiz${card.label}${score}${tagline}${rate}`);
  if (!fonts) return new ImageResponse(<Fallback card={card} />, cardSize);

  return new ImageResponse(
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        width: "100%",
        height: "100%",
        padding: "64px 80px",
        background: colors.background,
        color: colors.text,
        fontFamily: "Noto Sans JP",
      }}
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ fontSize: 26, letterSpacing: 6, color: colors.muted }}>QUIZ RESULT</div>
        <div style={{ fontSize: 60, fontWeight: 700, lineHeight: 1.25 }}>{card.label}</div>
      </div>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 40 }}>
        <div style={{ fontSize: 180, fontWeight: 700, lineHeight: 1, color: colors.accent }}>{rate}</div>
        <div style={{ fontSize: 40, paddingBottom: 20 }}>{score}</div>
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 28, color: colors.muted }}>
        <div style={{ fontWeight: 700, color: colors.text }}>Quiz</div>
        <div>{tagline}</div>
      </div>
    </div>,
    { ...cardSize, fonts },
  );
}

/** `ImageResponse` が持っている英字のフォントで描けるものだけを出す */
function Fallback({ card }: { card?: ShareCard }) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        alignItems: "center",
        gap: 24,
        width: "100%",
        height: "100%",
        background: colors.background,
        color: colors.text,
      }}
    >
      <div style={{ fontSize: 64, fontWeight: 700 }}>Quiz</div>
      {/* 1 つの要素に置く文字は、1 つの文字列にする。分けると ImageResponse が flex を求める */}
      {card && <div style={{ fontSize: 140, fontWeight: 700, color: colors.accent }}>{`${rateOf(card)}%`}</div>}
      {card && <div style={{ fontSize: 40, color: colors.muted }}>{`${card.correct} / ${card.total}`}</div>}
    </div>
  );
}
