import { useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { ApiError, authorizedFetch } from "@/lib/api/fetcher";
import { useEnvironment } from "@/lib/environment-provider";
import { PROBE_PATH, type WakeResult, currentWake, onWakeStarted, wakeBackend } from "@/lib/wake";

export const colors = {
  text: "#1f2937",
  muted: "#6b7280",
  border: "#e5e7eb",
  surface: "#f3f4f6",
  primary: "#2563eb",
  danger: "#b91c1c",
  background: "#ffffff",
};

export function Screen({ children }: { children: ReactNode }) {
  return <View style={styles.screen}>{children}</View>;
}

export function Loading() {
  return (
    <View style={styles.center}>
      <ActivityIndicator />
    </View>
  );
}

export function Button({
  label,
  onPress,
  disabled,
  variant = "primary",
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  variant?: "primary" | "secondary";
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        variant === "secondary" && styles.secondaryButton,
        (pressed || disabled) && styles.dimmed,
      ]}
    >
      <Text style={[styles.buttonLabel, variant === "secondary" && styles.secondaryLabel]}>{label}</Text>
    </Pressable>
  );
}

/**
 * 読み込みの失敗を 1 行で出す。止まっている dev は、理由を分けて出し、起こすボタンを添える（web の api-error-alert と同じ）。
 * prod には起こす口がないため、ボタンを出さない（DEV-209）
 */
export function ErrorMessage({ error }: { error: unknown }) {
  const { environment } = useEnvironment();
  const stopped = error instanceof ApiError && error.serverMayBeStopped;
  const message = stopped
    ? "サーバーが止まっているか、起動の途中です。しばらくしてからお試しください"
    : error instanceof ApiError && error.problem?.detail
      ? error.problem.detail
      : "読み込めませんでした。通信の状態を確かめてください";
  if (!stopped || environment.name !== "dev") return <Text style={styles.error}>{message}</Text>;
  return (
    <View style={{ gap: 8 }}>
      <Text style={styles.error}>{message}</Text>
      <WakeServerButton />
    </View>
  );
}

/**
 * 止まっている dev を起こすボタン（DEV-209。web の `WakeServerButton`）。使えるようになったら、読み込みをやり直す。
 * 押したあとは、同じ画面のほかの案内も、移った先の画面の案内も、ボタンの代わりに起動中を出す
 */
function WakeServerButton() {
  const client = useQueryClient();
  const { environment } = useEnvironment();
  const [state, setState] = useState<"idle" | "waking" | WakeResult>("idle");

  useEffect(() => {
    let active = true;
    const follow = (wake: Promise<WakeResult> | null) => {
      if (!wake) return;
      setState("waking");
      void wake.then((result) => {
        if (active) setState(result);
      });
    };
    const unsubscribe = onWakeStarted(follow);
    follow(currentWake());
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  // 押したら、onWakeStarted を通して、このボタンもほかの案内も起動中になる。確かめる先は、押したときのつなぐ先に決める
  const wake = () =>
    void wakeBackend({
      request: () => authorizedFetch(environment, "/api/wake", { method: "POST" }),
      probe: () => authorizedFetch(environment, PROBE_PATH),
      onReady: () => client.invalidateQueries(),
    });

  return (
    <View style={{ gap: 8 }}>
      {(state === "idle" || state === "failed") && (
        <Button label="サーバーを起動する" variant="secondary" onPress={wake} />
      )}
      <View accessibilityLiveRegion="polite" style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
        {state === "waking" && <ActivityIndicator />}
        <Text style={[styles.muted, { flexShrink: 1 }]}>
          {state === "idle" && "使われていないと止まります。起動には 2 分ほどかかります"}
          {state === "waking" && "起動しています。使えるようになったら、自動で読み込み直します"}
          {state === "ready" && "使えるようになりました。保存などの操作は、もう一度お試しください"}
          {state === "failed" && "起動できませんでした。時間をおいてもう一度お試しください"}
          {state === "timeout" && "起動に時間がかかっています。少し待ってから、もう一度お試しください"}
        </Text>
      </View>
    </View>
  );
}

export const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background, padding: 16, gap: 16 },
  scroll: { flex: 1, backgroundColor: colors.background },
  scrollContent: { padding: 16, paddingBottom: 40, gap: 24 },
  panel: { gap: 12, borderWidth: 1, borderColor: colors.border, borderRadius: 14, padding: 16 },
  notice: {
    gap: 10,
    borderWidth: 1,
    borderColor: "#fcd34d",
    backgroundColor: "#fffbeb",
    borderRadius: 14,
    padding: 16,
  },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
  title: { fontSize: 22, fontWeight: "700", color: colors.text },
  text: { fontSize: 15, lineHeight: 22, color: colors.text },
  question: { fontSize: 19, lineHeight: 28, fontWeight: "700", color: colors.text },
  muted: { fontSize: 13, lineHeight: 20, color: colors.muted },
  error: { fontSize: 14, lineHeight: 20, color: colors.danger },
  button: { borderRadius: 8, backgroundColor: colors.primary, paddingVertical: 12, alignItems: "center" },
  secondaryButton: { backgroundColor: "transparent", borderWidth: 1, borderColor: colors.border },
  buttonLabel: { fontSize: 15, fontWeight: "600", color: "#ffffff" },
  secondaryLabel: { color: colors.text },
  dimmed: { opacity: 0.6 },
  row: { borderBottomWidth: 1, borderBottomColor: colors.border, paddingVertical: 14 },
  figure: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 6,
    backgroundColor: "#ffffff",
    padding: 8,
    overflow: "hidden",
  },
});
