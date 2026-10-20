import type { ReactNode } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { ApiError } from "@/lib/api/fetcher";

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

/** 読み込みの失敗を 1 行で出す。止まっている dev は、理由を分けて出す（web の api-error-alert と同じ） */
export function ErrorMessage({ error }: { error: unknown }) {
  const message =
    error instanceof ApiError && error.serverMayBeStopped
      ? "サーバーが止まっているか、起動の途中です。しばらくしてからお試しください"
      : error instanceof ApiError && error.problem?.detail
        ? error.problem.detail
        : "読み込めませんでした。通信の状態を確かめてください";
  return <Text style={styles.error}>{message}</Text>;
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
