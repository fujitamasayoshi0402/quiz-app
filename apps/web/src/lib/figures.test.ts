import { describe, expect, it } from "vitest";
import {
  figureIdOf,
  figureIdsIn,
  figureMarkdown,
  insertFigure,
  insertFigureLink,
  linkLabelOf,
  replaceFigure,
  svgFromDataUri,
} from "./figures";

const A = "0f8fad5b-d9cb-469f-a165-70867728950e";
const B = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

describe("解説図の参照", () => {
  it("URL が図を指しているときだけ ID を返す", () => {
    expect(figureIdOf(`figure:${A}`)).toBe(A);
    expect(figureIdOf(`FIGURE:${A.toUpperCase()}`)).toBe(A);
    expect(figureIdOf(`figure:${A}0`)).toBeUndefined();
    expect(figureIdOf(`https://example.com/figure:${A}`)).toBeUndefined();
  });

  it("本文が指す図を、出てきた順に重ねずに拾う。バックエンドと同じく、ID のあとに続く文字があれば拾わない", () => {
    const text = `${figureMarkdown(B)}\n[資料](figure:${A}) ${figureMarkdown(B, "同じ図")} figure:${A}0`;

    expect(figureIdsIn(text)).toEqual([B, A]);
  });

  it("図は、カーソルの位置に 1 行として入れる。位置が分からなければ末尾に入れる", () => {
    expect(insertFigure("前の文。後の文。", 4, A)).toBe(`前の文。\n![図](figure:${A})\n後の文。`);
    expect(insertFigure("前の文。\n後の文。", 5, A)).toBe(`前の文。\n![図](figure:${A})\n後の文。`);
    expect(insertFigure("本文", null, A)).toBe(`本文\n![図](figure:${A})`);
    expect(insertFigure("", 0, A)).toBe(`![図](figure:${A})`);
    expect(insertFigure("", 0, A, "画像")).toBe(`![画像](figure:${A})`);
  });

  it("PDF は、ファイル名を文字にしたリンクとして入れる。Markdown の記号になる括弧と改行は除く", () => {
    expect(insertFigureLink("本文", null, A, linkLabelOf("VPC の設計.pdf"))).toBe(`本文\n[VPC の設計](figure:${A})`);
    expect(linkLabelOf("[資料]\n別.pdf")).toBe("資料  別");
    expect(linkLabelOf(".pdf")).toBe("資料");
  });

  it("描き直した図に、本文の参照をすべて差し替える", () => {
    const text = `![構成図](figure:${A}) と [開く](figure:${A.toUpperCase()}) と ![別の図](figure:${B})`;

    expect(replaceFigure(text, A, B)).toBe(`![構成図](figure:${B}) と [開く](figure:${B}) と ![別の図](figure:${B})`);
  });

  it("書き出された SVG を、日本語のラベルを崩さずに戻す", () => {
    const svg = '<svg xmlns="http://www.w3.org/2000/svg"><text>構成図</text></svg>';
    const base64 = btoa(String.fromCharCode(...new TextEncoder().encode(svg)));

    expect(svgFromDataUri(`data:image/svg+xml;base64,${base64}`)).toBe(svg);
    expect(svgFromDataUri(`data:image/svg+xml,${encodeURIComponent(svg)}`)).toBe(svg);
  });
});
