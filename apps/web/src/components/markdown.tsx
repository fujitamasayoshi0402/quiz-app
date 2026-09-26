import { useId } from "react";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkBreaks from "remark-breaks";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";

/**
 * 管理者が書いた Markdown（解説など）を整形して表示する（ADR-0018）。
 *
 * **生の HTML は通さない。** react-markdown は、rehype-raw を足さない限り HTML を文字として出す。
 * `javascript:` などの URL も既定の変換（`defaultUrlTransform`）が空にする。
 * どちらも設定ひとつで崩れるため、`markdown.test.tsx` で固定している。
 */
export function Markdown({ children, className }: { children: string; className?: string }) {
  // 脚注の id の接頭辞。結果画面のように 1 画面に複数並ぶと、既定の接頭辞では id が重なる
  const prefix = `md${useId().replace(/[^a-zA-Z0-9]/g, "")}-`;

  return (
    <div className={cn("space-y-3 text-sm leading-relaxed break-words", className)}>
      <ReactMarkdown
        // 改行をそのまま改行にする。Markdown にする前の解説は、改行だけで行を分けている
        remarkPlugins={[remarkGfm, remarkBreaks]}
        remarkRehypeOptions={{
          clobberPrefix: prefix,
          footnoteLabel: "脚注",
          footnoteBackLabel: "本文へ戻る",
          // 変換の既定と同じ値だが、ここに書く。Tailwind はソースに現れたクラスしか生成せず、書かないと見出しが画面に出る
          footnoteLabelProperties: { className: ["sr-only"] },
        }}
        components={components}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}

/** react-markdown が渡す構文木（`node`）は DOM に出さない。クラスは既定のものに足す。 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars -- node は取り除くために取り出している
function attrs<P extends { className?: string }>(base: string, { node, className, ...props }: P & ExtraProps) {
  return { ...props, className: cn(base, className) };
}

const heading = "font-semibold";

const components: Components = {
  // 見出しは 2 段下げる。解説はページの一部で、`#` をそのまま h1 にすると文書の構造が崩れる
  h1: (props) => <h3 {...attrs(cn(heading, "text-base"), props)} />,
  h2: (props) => <h4 {...attrs(heading, props)} />,
  h3: (props) => <h5 {...attrs(heading, props)} />,
  h4: (props) => <h6 {...attrs(heading, props)} />,
  h5: (props) => <h6 {...attrs(heading, props)} />,
  h6: (props) => <h6 {...attrs(heading, props)} />,
  ul: (props) => (
    <ul {...attrs("list-disc space-y-1 pl-5 [&.contains-task-list]:list-none [&.contains-task-list]:pl-0", props)} />
  ),
  ol: (props) => <ol {...attrs("list-decimal space-y-1 pl-5", props)} />,
  // 外へのリンクは新しいタブで開く。出題の途中の画面から離れさせず、開いた先にこのページを触らせない。
  // 脚注のようなページ内のリンク（`#`）は、そのまま移る
  a: (props) => {
    const external = !props.href?.startsWith("#");
    return (
      <a
        {...attrs("text-primary underline underline-offset-4", props)}
        target={external ? "_blank" : undefined}
        rel={external ? "noopener noreferrer" : undefined}
      />
    );
  },
  // 画像は出さず、代わりの文字だけを出す。外部の画像は、読み込むだけで見た人の情報が相手に渡る。
  // 図は、署名付き URL で配る解説図（ADR-0017）で扱う
  img: ({ alt }) => (alt ? <span>{alt}</span> : null),
  code: (props) => <code {...attrs("bg-muted rounded px-1 py-0.5 font-mono text-[0.9em]", props)} />,
  pre: (props) => (
    <pre
      {...attrs(
        "bg-muted overflow-x-auto rounded-md p-3 font-mono text-xs leading-normal [&_code]:bg-transparent [&_code]:p-0",
        props,
      )}
    />
  ),
  blockquote: (props) => <blockquote {...attrs("text-muted-foreground border-l-2 pl-3", props)} />,
  // 狭い画面では表だけを横に送る。ページ全体に横スクロールを出さない。
  // セルには最小の幅を持たせる。持たせないと、列が 1 文字の幅まで縮んで縦に割れる
  table: (props) => (
    <div className="overflow-x-auto">
      <table {...attrs("w-full border-collapse text-left", props)} />
    </div>
  ),
  th: (props) => <th {...attrs("min-w-24 border px-2 py-1 font-semibold", props)} />,
  td: (props) => <td {...attrs("min-w-24 border px-2 py-1", props)} />,
  hr: (props) => <hr {...attrs("border-border", props)} />,
};
