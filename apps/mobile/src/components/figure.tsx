import { useQuery } from "@tanstack/react-query";
import * as WebBrowser from "expo-web-browser";
import { useState } from "react";
import { ActivityIndicator, Alert, Image, Pressable, Text, View } from "react-native";
import { WebView } from "react-native-webview";
import { colors, styles } from "@/components/ui";
import { followFigure } from "@/lib/api/fetcher";
import { figurePath, figurePreviewPath } from "@/lib/figures";
import { svgAspectRatio, svgDocument } from "@/lib/svg";

type Preview = { kind: "svg"; svg: string; aspectRatio: number } | { kind: "image"; uri: string };

/**
 * 解説図を出す（ADR-0020、ADR-0032）。本文の中には図の画像（PDF なら 1 ページ目）を出し、押すと図そのものを開く。
 *
 * 取れないとき（消された図、配信の失敗）は代わりの文字を出し、解説の文は崩さない。
 * 背景は白にする。draw.io の図は背景が透明で、線と文字が黒い（web と同じ）
 */
export function Figure({ tenant, id, alt }: { tenant: string; id: string; alt: string }) {
  const preview = useQuery({
    queryKey: ["figure-preview", tenant, id],
    queryFn: () => loadPreview(figurePreviewPath(tenant, id)),
    // 中身ごと持つので、署名付き URL の期限（5〜10 分）が切れても描ける。図は変わらない
    staleTime: Infinity,
  });

  if (preview.isPending) {
    return (
      <View style={[styles.figure, { height: 120, justifyContent: "center" }]}>
        <ActivityIndicator />
      </View>
    );
  }
  if (preview.isError) {
    return <Text style={styles.muted}>{alt ? `（図を表示できません: ${alt}）` : "（図を表示できません）"}</Text>;
  }
  return (
    <Pressable
      accessibilityRole="imagebutton"
      accessibilityLabel={alt || "図"}
      accessibilityHint="図を開いて拡大します"
      onPress={() => void openFigure(tenant, id)}
      style={styles.figure}
    >
      {preview.data.kind === "svg" ? (
        <SvgFigure svg={preview.data.svg} aspectRatio={preview.data.aspectRatio} />
      ) : (
        <ImageFigure uri={preview.data.uri} />
      )}
    </Pressable>
  );
}

/**
 * 図そのもの（PDF なら PDF）を、システムのブラウザの画面で開く。拡大して見られる。
 * 署名付き URL の期限は短いため、押すたびに取り直す
 */
export async function openFigure(tenant: string, id: string): Promise<void> {
  try {
    const response = await followFigure(figurePath(tenant, id), "HEAD");
    await WebBrowser.openBrowserAsync(response.url);
  } catch {
    Alert.alert("図を開けませんでした", "通信の状態を確かめて、もう一度押してください");
  }
}

async function loadPreview(path: string): Promise<Preview> {
  const response = await followFigure(path, "GET");
  const type = response.headers.get("content-type") ?? "";
  if (type.startsWith("image/svg+xml")) {
    const svg = await response.text();
    // 比が読めない SVG は 4:3 で描く。はみ出したところは縮んで収まる
    return { kind: "svg", svg, aspectRatio: svgAspectRatio(svg) ?? 4 / 3 };
  }
  if (type.startsWith("image/")) return { kind: "image", uri: await dataUri(await response.blob()) };
  throw new Error(`図として描けない種類です: ${type}`);
}

function dataUri(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error ?? new Error("図を読めませんでした"));
    reader.readAsDataURL(blob);
  });
}

function SvgFigure({ svg, aspectRatio }: { svg: string; aspectRatio: number }) {
  return (
    // 押したときは、外の Pressable が受ける。WebView は描くだけで、触らせない
    <View pointerEvents="none" style={{ width: "100%", aspectRatio }}>
      <WebView
        source={{ html: svgDocument(svg) }}
        originWhitelist={["about:*"]}
        javaScriptEnabled={false}
        scrollEnabled={false}
        // 最初の文書のほかへは移らせない
        onShouldStartLoadWithRequest={(request) => request.url.startsWith("about:")}
        style={{ backgroundColor: colors.background }}
      />
    </View>
  );
}

function ImageFigure({ uri }: { uri: string }) {
  const [aspectRatio, setAspectRatio] = useState(4 / 3);
  return (
    <Image
      source={{ uri }}
      resizeMode="contain"
      onLoad={({ nativeEvent: { source } }) => {
        if (source.width > 0 && source.height > 0) setAspectRatio(source.width / source.height);
      }}
      style={{ width: "100%", aspectRatio }}
    />
  );
}
