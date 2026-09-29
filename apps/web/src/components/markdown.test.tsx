import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { Markdown } from "./markdown";

function render(markdown: string, tenant?: string) {
  return renderToStaticMarkup(<Markdown tenant={tenant}>{markdown}</Markdown>);
}

const FIGURE = "0f8fad5b-d9cb-469f-a165-70867728950e";

describe("Markdown", () => {
  it("見出し・箇条書き・コード・表を整形する", () => {
    const html = render(
      ["# 見出し", "", "- **太字** と `code`", "", "```ts", "const a = 1;", "```", "", "| a | b |", "| - | - |", "| 1 | 2 |"].join(
        "\n",
      ),
    );

    // 見出しは 2 段下げる
    expect(html).toContain(">見出し</h3>");
    expect(html).toMatch(/<li><strong>太字<\/strong> と <code[^>]*>code<\/code><\/li>/);
    expect(html).toMatch(/<pre[^>]*><code[^>]*language-ts[^>]*>const a = 1;\n<\/code><\/pre>/);
    expect(html).toContain("<table");
  });

  it("改行をそのまま改行にする", () => {
    expect(render("1 行目\n2 行目")).toContain("1 行目<br/>\n2 行目");
  });

  describe("書かれたものを実行させない", () => {
    it.each([
      "<script>alert(1)</script>",
      '<img src="x" onerror="alert(1)">',
      '<a href="https://example.com" onclick="alert(1)">x</a>',
      '<iframe src="https://example.com"></iframe>',
      "<style>body { display: none }</style>",
    ])("生の HTML は文字として出す: %s", (source) => {
      const html = render(source);

      expect(html).not.toMatch(/<(script|img|iframe|style)\b/);
      expect(html).not.toMatch(/<a\b/);
      expect(html).toContain("&lt;");
    });

    it.each([
      "[x](javascript:alert(1))",
      "[x](JavaScript:alert(1))",
      "[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)",
      "[x](vbscript:msgbox(1))",
      "<javascript:alert(1)>",
      "[x]: javascript:alert(1)\n\n[x]",
    ])("危ない URL はリンクにしない: %s", (source) => {
      const html = render(source);

      expect(html).not.toMatch(/href="(?!")/);
    });
  });

  it("外へのリンクは、開いた先にこのページを触らせない", () => {
    const html = render("[資料](https://example.com/doc)");

    expect(html).toContain('href="https://example.com/doc"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  it("画像は読み込まず、代わりの文字を出す", () => {
    const html = render("![構成図](https://example.com/a.png)");

    expect(html).not.toContain("<img");
    expect(html).not.toContain("example.com");
    expect(html).toContain("構成図");
  });

  describe("解説図（figure:）", () => {
    it("図を指す画像だけを、テナントの API から、本文の中に出す画像（PDF なら 1 ページ目）として取って出す", () => {
      const html = render(`![構成図](figure:${FIGURE})`, "demo");

      expect(html).toContain(`<img src="/api/t/demo/play/figures/${FIGURE}/preview" alt="構成図"`);
    });

    it("図は押すと、図そのもの（PDF なら PDF）を新しいタブで開き、拡大して見られる", () => {
      const html = render(`![構成図](figure:${FIGURE})`, "demo");

      expect(html).toMatch(
        new RegExp(`<a href="/api/t/demo/play/figures/${FIGURE}" target="_blank" rel="noopener noreferrer"[^>]*><img `),
      );
    });

    it("ID の大文字と小文字は区別しない", () => {
      expect(render(`![構成図](figure:${FIGURE.toUpperCase()})`, "demo")).toContain(`/play/figures/${FIGURE}"`);
    });

    it("図を指すリンクは、図を新しいタブで開く", () => {
      const html = render(`[資料](figure:${FIGURE})`, "demo");

      expect(html).toContain(`href="/api/t/demo/play/figures/${FIGURE}"`);
      expect(html).toContain('target="_blank"');
      expect(html).toContain('rel="noopener noreferrer"');
    });

    it.each([
      ["テナントが分からない", `![構成図](figure:${FIGURE})`, undefined],
      ["ID が UUID の形でない", "![構成図](figure:../../admin/quizzes)", "demo"],
      ["ID のあとに文字が続く", `![構成図](figure:${FIGURE}/source)`, "demo"],
      ["図を指していない", "![構成図](/api/t/demo/play/figures/x)", "demo"],
    ])("%sときは、画像を出さず代わりの文字を出す", (_, source, tenant) => {
      const html = render(source, tenant);

      expect(html).not.toContain("<img");
      expect(html).toContain("構成図");
    });

    it("図を指す形でないリンクは、figure: のまま残さない", () => {
      expect(render("[資料](figure:../../admin/quizzes)", "demo")).not.toMatch(/href="(?!")/);
    });

    it("テナントの名前は URL の中で文字として扱う", () => {
      expect(render(`![構成図](figure:${FIGURE})`, "a/b?c")).toContain('src="/api/t/a%2Fb%3Fc/play/figures/');
    });
  });

  it("1 画面に並べても、脚注の id が重ならない", () => {
    const source = "本文[^1]\n\n[^1]: 脚注";
    const html = renderToStaticMarkup(
      <>
        <Markdown>{source}</Markdown>
        <Markdown>{source}</Markdown>
      </>,
    );
    // 脚注の見出しの id（footnote-label）は変換の中で固定されていて変えられない。中身はどれも「脚注」で、リンク先にもならない
    const ids = [...html.matchAll(/ id="([^"]+)"/g)].map(([, id]) => id).filter((id) => id !== "footnote-label");

    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    // 脚注へのリンクは同じタブのまま移る
    expect(html).not.toMatch(/href="#[^"]*"[^>]*target=/);
  });
});
