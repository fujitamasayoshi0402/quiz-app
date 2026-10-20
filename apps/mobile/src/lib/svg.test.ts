import { describe, expect, it } from "vitest";
import { svgAspectRatio, svgDocument } from "./svg";

describe("svgAspectRatio", () => {
  it("viewBox から比を出す", () => {
    expect(svgAspectRatio('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">')).toBe(2);
  });

  it("viewBox は width と height より先に見る", () => {
    expect(svgAspectRatio('<svg width="100%" height="100%" viewBox="-0.5 -0.5 300 100">')).toBe(3);
  });

  it("viewBox がなければ、px か単位のない width と height から出す", () => {
    expect(svgAspectRatio("<?xml version=\"1.0\"?><svg width='300px' height='150'>")).toBe(2);
  });

  it.each([
    ["ルートの svg がない", "<div></div>"],
    ["比を決められない単位", '<svg width="100%" height="10em">'],
    ["高さが 0", '<svg viewBox="0 0 100 0">'],
  ])("%sときは null", (_, svg) => {
    expect(svgAspectRatio(svg)).toBeNull();
  });
});

describe("svgDocument", () => {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script><text>"ラベル"</text></svg>';

  it("SVG を文書に直接は埋め込まず、<img> の data URI として読ませる", () => {
    const html = svgDocument(svg);

    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<svg");
    expect(html).toContain('<img src="data:image/svg+xml;charset=utf-8,');
    expect(decodeURIComponent(html.match(/<img src="data:image\/svg\+xml;charset=utf-8,([^"]*)"/)![1])).toBe(svg);
  });

  it("配信のときと同じく、外のものを読み込ませない CSP を付ける", () => {
    expect(svgDocument(svg)).toContain("default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:");
  });
});
