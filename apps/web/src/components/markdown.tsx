import { useId, useMemo } from "react";
import ReactMarkdown, { type Components, type ExtraProps, defaultUrlTransform } from "react-markdown";
import remarkBreaks from "remark-breaks";
import remarkGfm from "remark-gfm";
import { FigureImage } from "@/components/figure-image";
import { figureIdOf, figurePreviewUrl, figureUrl } from "@/lib/figures";
import { cn } from "@/lib/utils";

/**
 * 管理者が書いた Markdown（解説など）を整形して表示する（ADR-0018）。
 *
 * **生の HTML は通さない。** react-markdown は、rehype-raw を足さない限り HTML を文字として出す。
 * `javascript:` などの URL も既定の変換（`defaultUrlTransform`）が空にする。
 * どちらも設定ひとつで崩れるため、`markdown.test.tsx` で固定している。
 *
 * **画像は、解説図を指したもの（`figure:<図の ID>`）だけを出す**（ADR-0020）。図は [tenant] の API から取る。
 * [tenant] を渡さなければ、図も出さない。
 */
export function Markdown({ children, className, tenant }: { children: string; className?: string; tenant?: string }) {
  // 脚注の id の接頭辞。結果画面のように 1 画面に複数並ぶと、既定の接頭辞では id が重なる
  const prefix = `md${useId().replace(/[^a-zA-Z0-9]/g, "")}-`;
  const components = useMemo(() => ({ ...baseComponents, ...figureComponents(tenant) }), [tenant]);

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
        urlTransform={urlTransform}
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

/** 図を指す URL（`figure:<図の ID>`）だけを残し、ほかは既定の変換に任せる。`figure:` は下の部品が API の URL に置き換える */
function urlTransform(url: string): string {
  return figureIdOf(url) ? url : defaultUrlTransform(url);
}

const linkClass = "text-primary underline underline-offset-4";

/** 図を指す画像とリンク。どちらも、図を指していなければ既定の扱い（画像は出さない、リンクはそのまま）にする */
function figureComponents(tenant: string | undefined): Components {
  return {
    img: ({ src, alt }) => {
      const id = figureIdOf(typeof src === "string" ? src : undefined);
      // 図でない画像は出さず、代わりの文字だけを出す。外部の画像は、読み込むだけで見た人の情報が相手に渡る
      if (!id || !tenant) return alt ? <span>{alt}</span> : null;
      // 本文の中には図の画像（PDF なら 1 ページ目）を出し、押すと図そのものを開く（ADR-0021）
      return <FigureImage src={figurePreviewUrl(tenant, id)} href={figureUrl(tenant, id)} alt={alt ?? ""} />;
    },
    // 外へのリンクと図は新しいタブで開く。出題の途中の画面から離れさせず、開いた先にこのページを触らせない。
    // 脚注のようなページ内のリンク（`#`）は、そのまま移る
    a: (props) => {
      const id = figureIdOf(props.href);
      const href = id ? tenant && figureUrl(tenant, id) : props.href;
      const external = !href?.startsWith("#");
      return (
        <a
          {...attrs(linkClass, props)}
          href={href || undefined}
          target={external ? "_blank" : undefined}
          rel={external ? "noopener noreferrer" : undefined}
        />
      );
    },
  };
}

const heading = "font-semibold";

const baseComponents: Components = {
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
