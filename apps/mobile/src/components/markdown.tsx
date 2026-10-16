import * as WebBrowser from "expo-web-browser";
import { Fragment, useMemo } from "react";
import { Linking, Platform, ScrollView, StyleSheet, Text, View } from "react-native";
import { Figure, openFigure } from "@/components/figure";
import { colors } from "@/components/ui";
import { type Block, type Inline, parseMarkdown } from "@/lib/markdown";

/**
 * 管理者が書いた Markdown（解説）を整形して表示する（ADR-0018、ADR-0032）。
 *
 * 何を出してよいか（生の HTML は文字に、画像は `figure:` の図だけ、URL は http・https・mailto だけ）は、
 * `lib/markdown.ts` が決める。ここは、そこで作った節を React Native の部品に写すだけにする。
 * [tenant] を渡さなければ、図も出さない（web と同じ）
 */
export function Markdown({ children, tenant }: { children: string; tenant?: string }) {
  const blocks = useMemo(() => parseMarkdown(children), [children]);
  return (
    <View style={s.root}>
      <Blocks blocks={blocks} tenant={tenant} />
    </View>
  );
}

function Blocks({ blocks, tenant }: { blocks: Block[]; tenant?: string }) {
  return blocks.map((block, index) => <BlockView key={index} block={block} tenant={tenant} />);
}

function BlockView({ block, tenant }: { block: Block; tenant?: string }) {
  switch (block.type) {
    case "paragraph":
      return (
        <Text style={s.text}>
          <Inlines inlines={block.children} tenant={tenant} />
        </Text>
      );
    case "heading":
      // 見出しは本文より少し大きくするだけにする。解説は画面の一部で、画面の見出しより目立たせない
      return (
        <Text accessibilityRole="header" style={[s.text, s.heading, block.depth === 1 && s.heading1]}>
          <Inlines inlines={block.children} tenant={tenant} />
        </Text>
      );
    case "figure":
      return tenant ? (
        <Figure tenant={tenant} id={block.id} alt={block.alt} />
      ) : (
        <Text style={s.text}>{block.alt}</Text>
      );
    case "list":
      return (
        <View style={s.list}>
          {block.items.map((item, index) => (
            <View key={index} style={s.listItem}>
              <Text style={[s.text, s.marker]}>
                {item.checked !== null ? (item.checked ? "☑" : "☐") : block.ordered ? `${block.start + index}.` : "•"}
              </Text>
              <View style={s.listBody}>
                <Blocks blocks={item.children} tenant={tenant} />
              </View>
            </View>
          ))}
        </View>
      );
    case "code":
      // 長い行は折り返さず、コードだけを横に送る
      return (
        <ScrollView horizontal style={s.codeBlock} contentContainerStyle={s.codeBlockContent}>
          <Text style={s.codeText}>{block.value}</Text>
        </ScrollView>
      );
    case "blockquote":
      return (
        <View style={s.blockquote}>
          <Blocks blocks={block.children} tenant={tenant} />
        </View>
      );
    case "table":
      // 狭い画面では表だけを横に送る。列の幅は揃えて固定する。中身に合わせると、行ごとに列がずれる
      return (
        <ScrollView horizontal>
          <View style={s.table}>
            {[block.header, ...block.rows].map((row, rowIndex) => (
              <View key={rowIndex} style={s.tableRow}>
                {row.map((cell, cellIndex) => (
                  <Text key={cellIndex} style={[s.text, s.tableCell, rowIndex === 0 && s.strong]}>
                    <Inlines inlines={cell} tenant={tenant} />
                  </Text>
                ))}
              </View>
            ))}
          </View>
        </ScrollView>
      );
    case "thematicBreak":
      return <View style={s.rule} />;
    case "footnotes":
      return (
        <View style={s.footnotes} accessibilityLabel="脚注">
          {block.items.map((item) => (
            <View key={item.number} style={s.listItem}>
              <Text style={[s.text, s.marker]}>{item.number}.</Text>
              <View style={s.listBody}>
                <Blocks blocks={item.children} tenant={tenant} />
              </View>
            </View>
          ))}
        </View>
      );
  }
}

function Inlines({ inlines, tenant }: { inlines: Inline[]; tenant?: string }) {
  return inlines.map((inline, index) => (
    <Fragment key={index}>
      <InlineView inline={inline} tenant={tenant} />
    </Fragment>
  ));
}

function InlineView({ inline, tenant }: { inline: Inline; tenant?: string }) {
  switch (inline.type) {
    case "text":
      return inline.value;
    case "break":
      return "\n";
    case "strong":
    case "emphasis":
    case "delete":
      return (
        <Text style={s[inline.type]}>
          <Inlines inlines={inline.children} tenant={tenant} />
        </Text>
      );
    case "code":
      return <Text style={s.code}>{inline.value}</Text>;
    case "link":
      return (
        <Text accessibilityRole="link" style={s.link} onPress={() => void openLink(inline.url)}>
          <Inlines inlines={inline.children} tenant={tenant} />
        </Text>
      );
    case "figureLink":
      if (!tenant) return <Inlines inlines={inline.children} />;
      return (
        <Text accessibilityRole="link" style={s.link} onPress={() => void openFigure(tenant, inline.id)}>
          <Inlines inlines={inline.children} tenant={tenant} />
        </Text>
      );
    case "footnoteReference":
      return <Text style={s.footnoteReference}>[{inline.number}]</Text>;
  }
}

/** web は新しいタブで開く。アプリはシステムのブラウザの画面で開き、閉じれば出題の途中に戻る。メールはメールのアプリで開く */
async function openLink(url: string) {
  if (url.toLowerCase().startsWith("mailto:")) {
    await Linking.openURL(url);
  } else {
    await WebBrowser.openBrowserAsync(url);
  }
}

const monospace = Platform.select({ ios: "Menlo", default: "monospace" });

const s = StyleSheet.create({
  root: { gap: 12 },
  text: { fontSize: 15, lineHeight: 24, color: colors.text },
  heading: { fontWeight: "700" },
  heading1: { fontSize: 16 },
  strong: { fontWeight: "700" },
  emphasis: { fontStyle: "italic" },
  delete: { textDecorationLine: "line-through" },
  code: { fontFamily: monospace, fontSize: 14, backgroundColor: colors.surface },
  link: { color: colors.primary, textDecorationLine: "underline" },
  footnoteReference: { fontSize: 11, color: colors.primary },
  list: { gap: 4 },
  listItem: { flexDirection: "row", gap: 6 },
  marker: { minWidth: 16 },
  listBody: { flex: 1, gap: 8 },
  codeBlock: { borderRadius: 6, backgroundColor: colors.surface },
  codeBlockContent: { padding: 12 },
  codeText: { fontFamily: monospace, fontSize: 13, lineHeight: 19, color: colors.text },
  blockquote: { borderLeftWidth: 2, borderLeftColor: colors.border, paddingLeft: 12, gap: 8, opacity: 0.8 },
  table: { borderTopWidth: 1, borderLeftWidth: 1, borderColor: colors.border },
  tableRow: { flexDirection: "row" },
  tableCell: {
    width: 140,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRightWidth: 1,
    borderBottomWidth: 1,
    borderColor: colors.border,
  },
  rule: { height: 1, backgroundColor: colors.border },
  footnotes: { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 8, gap: 4 },
});
