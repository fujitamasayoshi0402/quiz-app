import type {
  Definition,
  FootnoteDefinition,
  ListItem,
  Nodes,
  PhrasingContent,
  Root,
  RootContent,
  TableRow,
} from "mdast";
import remarkBreaks from "remark-breaks";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";
import { figureIdOf } from "@/lib/figures";

/**
 * 管理者が書いた Markdown（解説）を、画面に写せる形にする（ADR-0018、ADR-0032）。
 *
 * web と同じパーサ（remark と GFM、改行）で構文木にし、**出してよいものだけ**をこの形に移す。
 * 画面の部品（`components/markdown.tsx`）は、ここで作ったものだけを描く。崩れると困る性質は `markdown.test.ts` で固定する。
 *
 * - **生の HTML は文字として出す**
 * - **画像は、解説図を指したもの（`figure:<図の ID>`）だけを出す。** ほかの画像は読み込まず、代わりの文字を出す
 * - リンクにするのは `http:`、`https:`、`mailto:` と、図を指すものだけ。ほかは文字として出す
 */

export type Inline =
  | { type: "text"; value: string }
  | { type: "strong" | "emphasis" | "delete"; children: Inline[] }
  | { type: "code"; value: string }
  | { type: "break" }
  | { type: "link"; url: string; children: Inline[] }
  | { type: "figureLink"; id: string; children: Inline[] }
  | { type: "footnoteReference"; number: number };

export type Block =
  | { type: "paragraph"; children: Inline[] }
  | { type: "heading"; depth: number; children: Inline[] }
  | { type: "figure"; id: string; alt: string }
  | { type: "list"; ordered: boolean; start: number; items: { checked: boolean | null; children: Block[] }[] }
  | { type: "code"; lang: string | null; value: string }
  | { type: "blockquote"; children: Block[] }
  | { type: "table"; header: Inline[][]; rows: Inline[][][] }
  | { type: "thematicBreak" }
  | { type: "footnotes"; items: { number: number; children: Block[] }[] };

const parser = unified().use(remarkParse).use(remarkGfm).use(remarkBreaks);

export function parseMarkdown(source: string): Block[] {
  // remark-breaks は変換（run）で改行を `break` にするため、parse だけでは足りない
  const root = parser.runSync(parser.parse(source)) as Root;
  return new Converter(root).convert();
}

const SAFE_URL = /^(https?|mailto):/i;

class Converter {
  private readonly definitions = new Map<string, Definition>();
  private readonly footnoteDefinitions = new Map<string, FootnoteDefinition>();
  /** 脚注の番号。本文で最初に参照した順に振る（GFM と同じ）。参照されない脚注は出さない */
  private readonly footnoteNumbers = new Map<string, number>();

  constructor(private readonly root: Root) {
    for (const node of root.children) {
      if (node.type === "definition") this.definitions.set(node.identifier, node);
      if (node.type === "footnoteDefinition") this.footnoteDefinitions.set(node.identifier, node);
    }
  }

  convert(): Block[] {
    const blocks = this.blocks(this.root.children);
    // 脚注の中で、まだ番号のない脚注を参照すると番号が増える。増えた分も続けて作る
    const footnotes: { number: number; children: Block[] }[] = [];
    for (let i = 0; i < this.footnoteNumbers.size; i++) {
      const [identifier, number] = [...this.footnoteNumbers.entries()][i];
      const definition = this.footnoteDefinitions.get(identifier);
      footnotes.push({ number, children: definition ? this.blocks(definition.children) : [] });
    }
    return footnotes.length > 0 ? [...blocks, { type: "footnotes", items: footnotes }] : blocks;
  }

  private blocks(nodes: RootContent[]): Block[] {
    return nodes.flatMap((node) => this.block(node));
  }

  private block(node: RootContent): Block[] {
    switch (node.type) {
      case "paragraph":
        return this.paragraph(node.children);
      case "heading":
        return [{ type: "heading", depth: node.depth, children: this.inlines(node.children) }];
      case "list":
        return [
          {
            type: "list",
            ordered: node.ordered ?? false,
            start: node.start ?? 1,
            items: node.children.map((item: ListItem) => ({
              checked: item.checked ?? null,
              children: this.blocks(item.children),
            })),
          },
        ];
      case "code":
        return [{ type: "code", lang: node.lang ?? null, value: node.value }];
      case "blockquote":
        return [{ type: "blockquote", children: this.blocks(node.children) }];
      case "table": {
        const [header, ...rows] = node.children.map((row: TableRow) =>
          row.children.map((cell) => this.inlines(cell.children)),
        );
        return [{ type: "table", header: header ?? [], rows }];
      }
      case "thematicBreak":
        return [{ type: "thematicBreak" }];
      case "html":
        return [{ type: "paragraph", children: [{ type: "text", value: node.value }] }];
      // 定義は参照の側で使う。脚注は最後にまとめて出す
      case "definition":
      case "footnoteDefinition":
        return [];
      default:
        return this.fallbackBlock(node);
    }
  }

  /** 知らない節は、中の文字だけを出す。描けないからといって、書いたものを黙って消さない */
  private fallbackBlock(node: Nodes): Block[] {
    const value = textOf(node);
    return value ? [{ type: "paragraph", children: [{ type: "text", value }] }] : [];
  }

  /**
   * 段落の中の図は、段落を分けて 1 つの節として出す。React Native では、文字の途中に画像を置けない。
   * 図の前後の改行は、分けたところで要らなくなるので落とす
   */
  private paragraph(children: PhrasingContent[]): Block[] {
    const blocks: Block[] = [];
    let pending: Inline[] = [];
    const flush = () => {
      while (pending[0]?.type === "break") pending.shift();
      while (pending.at(-1)?.type === "break") pending.pop();
      if (pending.length > 0) blocks.push({ type: "paragraph", children: pending });
      pending = [];
    };
    for (const child of children) {
      const id = child.type === "image" ? figureIdOf(child.url) : this.figureReferenceOf(child);
      if (id) {
        flush();
        blocks.push({ type: "figure", id, alt: "alt" in child ? (child.alt ?? "") : "" });
      } else {
        pending.push(...this.inline(child));
      }
    }
    flush();
    return blocks;
  }

  private figureReferenceOf(node: PhrasingContent): string | undefined {
    if (node.type !== "imageReference") return undefined;
    return figureIdOf(this.definitions.get(node.identifier)?.url);
  }

  private inlines(nodes: PhrasingContent[]): Inline[] {
    return nodes.flatMap((node) => this.inline(node));
  }

  private inline(node: PhrasingContent): Inline[] {
    switch (node.type) {
      case "text":
        return [{ type: "text", value: node.value }];
      case "strong":
      case "emphasis":
      case "delete":
        return [{ type: node.type, children: this.inlines(node.children) }];
      case "inlineCode":
        return [{ type: "code", value: node.value }];
      case "break":
        return [{ type: "break" }];
      case "html":
        return [{ type: "text", value: node.value }];
      case "link":
        return this.link(node.url, node.children);
      case "linkReference": {
        const url = this.definitions.get(node.identifier)?.url;
        return url === undefined ? this.inlines(node.children) : this.link(url, node.children);
      }
      // 段落の外（見出し、表、リンクの中）の画像は、図でも代わりの文字にする
      case "image":
      case "imageReference":
        return node.alt ? [{ type: "text", value: node.alt }] : [];
      case "footnoteReference":
        return this.footnoteReference(node.identifier);
      default:
        return textOf(node) ? [{ type: "text", value: textOf(node) }] : [];
    }
  }

  private link(url: string, children: PhrasingContent[]): Inline[] {
    const inlines = this.inlines(children);
    const figureId = figureIdOf(url);
    if (figureId) return [{ type: "figureLink", id: figureId, children: inlines }];
    if (SAFE_URL.test(url.trim())) return [{ type: "link", url: url.trim(), children: inlines }];
    return inlines;
  }

  private footnoteReference(identifier: string): Inline[] {
    if (!this.footnoteDefinitions.has(identifier)) return [];
    let number = this.footnoteNumbers.get(identifier);
    if (number === undefined) {
      number = this.footnoteNumbers.size + 1;
      this.footnoteNumbers.set(identifier, number);
    }
    return [{ type: "footnoteReference", number }];
  }
}

function textOf(node: Nodes): string {
  if ("value" in node && typeof node.value === "string") return node.value;
  if ("children" in node) return (node.children as Nodes[]).map(textOf).join("");
  return "";
}
