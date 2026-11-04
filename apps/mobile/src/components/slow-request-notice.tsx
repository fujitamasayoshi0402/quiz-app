import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { AccessibilityInfo, ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { colors, styles } from "@/components/ui";
import { type SlowRequestState, watchSlowRequests } from "@/lib/slow-requests";

const TITLES: Record<Exclude<SlowRequestState, "none">, string> = {
  waiting: "サーバーの応答を待っています",
  retrying: "応答がなかったため、もう一度試しています",
};

const DETAIL = "しばらく使われていなかったときは、データベースの起動に 20 秒ほどかかります";

/**
 * 読み込みや保存が数秒を超えたら、画面の下に案内を出す（DEV-226。web の `slow-request-notice.tsx` と同じ）。
 *
 * prod の Aurora は使っていない間は一時停止していて、止まっている DB への最初の要求は 20 秒ほどかかる。
 * その間くるくるのままだと、壊れたのか待てばよいのかが分からない。
 *
 * 遅い理由が DB の起動だとは、画面からは分からない。そのため「起動している」とは言い切らない。
 * 押せるものは置かず、下にあるボタンの操作も妨げない。
 */
export function SlowRequestNotice() {
  const client = useQueryClient();
  const insets = useSafeAreaInsets();
  const [state, setState] = useState<SlowRequestState>("none");
  useEffect(() => watchSlowRequests(client, setState), [client]);

  useEffect(() => {
    if (state !== "none") AccessibilityInfo.announceForAccessibility(`${TITLES[state]}。${DETAIL}`);
  }, [state]);

  if (state === "none") return null;
  return (
    <View style={[notice.overlay, { bottom: insets.bottom + 16 }]}>
      <View style={notice.card}>
        <ActivityIndicator />
        <View style={notice.body}>
          <Text style={[styles.text, notice.title]}>{TITLES[state]}</Text>
          <Text style={styles.muted}>{DETAIL}</Text>
        </View>
      </View>
    </View>
  );
}

const notice = StyleSheet.create({
  overlay: { position: "absolute", left: 16, right: 16, alignItems: "center", pointerEvents: "none" },
  card: {
    flexDirection: "row",
    alignItems: "flex-start",
    gap: 12,
    maxWidth: 384,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    backgroundColor: colors.background,
    paddingHorizontal: 16,
    paddingVertical: 12,
    shadowColor: "#000000",
    shadowOpacity: 0.15,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
    elevation: 4,
  },
  body: { flexShrink: 1, gap: 2 },
  title: { fontWeight: "600" },
});
