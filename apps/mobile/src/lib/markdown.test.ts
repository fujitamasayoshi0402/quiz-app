import { describe, expect, it } from "vitest";
import { type Block, type Inline, parseMarkdown } from "./markdown";

const FIGURE = "0f8fad5b-d9cb-469f-a165-70867728950e";

/** 作ったものを、画面の部品が受け取る形のまま JSON にする。出てはいけないものが紛れていないかを、文字列で確かめる */
function render(source: string): string {
  return JSON.stringify(parseMarkdown(source));
}

function text(value: string): Inline {
  return { type: "text", value };
}

describe("parseMarkdown", () => {
  it("見出し・箇条書き・コード・表を、web と同じパーサで節に分ける", () => {
    const blocks = parseMarkdown(
      [
        "# 見出し",
        "",
        "- **太字** と `code`",
        "",
        "```ts",
        "const a = 1;",
        "```",
        "",
        "| a | b |",
        "| - | - |",
        "| 1 | 2 |",
      ].join("\n"),
    );

    expect(blocks).toEqual<Block[]>([
      { type: "heading", depth: 1, children: [text("見出し")] },
      {
        type: "list",
        ordered: false,
        start: 1,
        items: [
          {
            checked: null,
            children: [
              {
                type: "paragraph",
                children: [{ type: "strong", children: [text("太字")] }, text(" と "), { type: "code", value: "code" }],
              },
            ],
          },
        ],
      },
      { type: "code", lang: "ts", value: "const a = 1;" },
      { type: "table", header: [[text("a")], [text("b")]], rows: [[[text("1")], [text("2")]]] },
    ]);
  });

  it("改行をそのまま改行にする", () => {
    expect(parseMarkdown("1 行目\n2 行目")).toEqual<Block[]>([
      { type: "paragraph", children: [text("1 行目"), { type: "break" }, text("2 行目")] },
    ]);
  });

  describe("書かれたものを実行させない", () => {
    it.each([
      "<script>alert(1)</script>",
      '<img src="x" onerror="alert(1)">',
      '<a href="https://example.com" onclick="alert(1)">x</a>',
      '<iframe src="https://example.com"></iframe>',
    ])("生の HTML は文字として出す: %s", (source) => {
      const output = render(source);

      expect(output).not.toMatch(/"type":"(link|figure|figureLink)"/);
      expect(output).toContain('"type":"text","value":"<');
    });

    it.each([
      "[x](javascript:alert(1))",
      "[x](JavaScript:alert(1))",
      "[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)",
      "[x](vbscript:msgbox(1))",
      "[x](/api/t/demo/admin/quizzes)",
      "<javascript:alert(1)>",
      "[x]: javascript:alert(1)\n\n[x]",
    ])("http、https、mailto でない URL はリンクにしない: %s", (source) => {
      expect(render(source)).not.toContain('"type":"link"');
    });
  });

  it.each([
    ["https://example.com/doc", "https://example.com/doc"],
    ["mailto:someone@example.com", "mailto:someone@example.com"],
  ])("外へのリンクはリンクにする: %s", (url, expected) => {
    expect(parseMarkdown(`[資料](${url})`)).toEqual<Block[]>([
      { type: "paragraph", children: [{ type: "link", url: expected, children: [text("資料")] }] },
    ]);
  });

  it("参照の形で書いたリンクも、定義の URL を使う", () => {
    expect(parseMarkdown("[資料][doc]\n\n[doc]: https://example.com/doc")).toEqual<Block[]>([
      { type: "paragraph", children: [{ type: "link", url: "https://example.com/doc", children: [text("資料")] }] },
    ]);
  });

  it("画像は読み込まず、代わりの文字を出す", () => {
    const output = render("![構成図](https://example.com/a.png)");

    expect(output).not.toContain("example.com");
    expect(output).not.toContain('"type":"figure"');
    expect(output).toContain("構成図");
  });

  describe("解説図（figure:）", () => {
    it("図を指す画像だけを、段落から分けて図として出す", () => {
      expect(parseMarkdown(`前の文\n![構成図](figure:${FIGURE})\n後の文`)).toEqual<Block[]>([
        { type: "paragraph", children: [text("前の文")] },
        { type: "figure", id: FIGURE, alt: "構成図" },
        { type: "paragraph", children: [text("後の文")] },
      ]);
    });

    it("ID の大文字と小文字は区別しない", () => {
      expect(parseMarkdown(`![構成図](figure:${FIGURE.toUpperCase()})`)).toEqual<Block[]>([
        { type: "figure", id: FIGURE, alt: "構成図" },
      ]);
    });

    it("図を指すリンクは、図を開くリンクにする", () => {
      expect(parseMarkdown(`[資料](figure:${FIGURE})`)).toEqual<Block[]>([
        { type: "paragraph", children: [{ type: "figureLink", id: FIGURE, children: [text("資料")] }] },
      ]);
    });

    it.each([
      ["ID が UUID の形でない", "![構成図](figure:../../admin/quizzes)"],
      ["ID のあとに文字が続く", `![構成図](figure:${FIGURE}/source)`],
      ["図を指していない", "![構成図](/api/t/demo/play/figures/x)"],
      ["見出しの中にある", `# ![構成図](figure:${FIGURE})`],
    ])("%sときは、図を出さず代わりの文字を出す", (_, source) => {
      const output = render(source);

      expect(output).not.toContain('"type":"figure"');
      expect(output).toContain("構成図");
    });

    it("図を指す形でないリンクは、リンクにしない", () => {
      expect(render("[資料](figure:../../admin/quizzes)")).not.toMatch(/"type":"(link|figureLink)"/);
    });
  });

  it("脚注は、参照した順に番号を振って最後にまとめる。参照されない脚注は出さない", () => {
    expect(parseMarkdown("本文[^b]と[^a]\n\n[^a]: A\n\n[^b]: B\n\n[^c]: C")).toEqual<Block[]>([
      {
        type: "paragraph",
        children: [
          text("本文"),
          { type: "footnoteReference", number: 1 },
          text("と"),
          { type: "footnoteReference", number: 2 },
        ],
      },
      {
        type: "footnotes",
        items: [
          { number: 1, children: [{ type: "paragraph", children: [text("B")] }] },
          { number: 2, children: [{ type: "paragraph", children: [text("A")] }] },
        ],
      },
    ]);
  });
});
