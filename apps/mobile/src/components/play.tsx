import * as WebBrowser from "expo-web-browser";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { colors, styles } from "@/components/ui";
import type { DeliveredChoice } from "@/lib/api/generated/model";
import type { Option } from "@/lib/play";

const LETTERS = ["A", "B", "C", "D"];

const correctColor = "#047857";
const wrongColor = "#b91c1c";

/** ラベル全体を押せる、1 つを選ぶ一覧。出題条件の各項目で使う（web の `OptionGroup`） */
export function OptionGroup({
  options,
  value,
  onChange,
}: {
  options: Option[];
  value: string | undefined;
  onChange: (value: string) => void;
}) {
  return (
    <View accessibilityRole="radiogroup" style={s.options}>
      {options.map((option) => {
        const checked = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{ checked }}
            onPress={() => onChange(option.value)}
            style={({ pressed }) => [s.card, checked && s.selected, pressed && styles.dimmed]}
          >
            <View style={[s.radio, checked && s.radioChecked]} />
            <View style={s.cardBody}>
              <Text style={styles.text}>{option.label}</Text>
              {option.hint && <Text style={styles.muted}>{option.hint}</Text>}
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

/**
 * 4 択。`reveal` を渡すと正解と選んだ選択肢を色と印で示し、選び直せなくする（web の `ChoiceList`）。
 * 回答と、結果画面の振り返りで共用する
 */
export function ChoiceList({
  choices,
  selectedId,
  onSelect,
  reveal,
}: {
  choices: DeliveredChoice[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  reveal?: { correctChoiceId: string };
}) {
  const locked = !onSelect || !!reveal;
  return (
    <View style={s.options}>
      {choices.map((choice, index) => {
        const selected = choice.id === selectedId;
        const correct = reveal?.correctChoiceId === choice.id;
        const wrongPick = !!reveal && selected && !correct;
        return (
          <Pressable
            key={choice.id}
            accessibilityRole="button"
            accessibilityState={{ selected, disabled: locked }}
            accessibilityLabel={`${LETTERS[index]}. ${choice.body}${correct ? "（正解）" : wrongPick ? "（選んだ選択肢）" : ""}`}
            disabled={locked}
            onPress={() => onSelect?.(choice.id)}
            style={({ pressed }) => [
              s.card,
              selected && !reveal && s.selected,
              correct && s.correct,
              wrongPick && s.wrong,
              reveal && !correct && !wrongPick && s.faded,
              pressed && styles.dimmed,
            ]}
          >
            <View
              style={[
                s.letter,
                selected && !reveal && s.letterSelected,
                correct && { backgroundColor: correctColor },
                wrongPick && { backgroundColor: wrongColor },
              ]}
            >
              <Text style={[s.letterText, (selected || correct) && s.letterTextOn]}>
                {correct ? "✓" : wrongPick ? "✕" : LETTERS[index]}
              </Text>
            </View>
            <Text style={[styles.text, s.cardBody]}>{choice.body}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** 正誤の印。未回答は、間違いと分けて出す（web の `Verdict`） */
export function Verdict({ correct, answered = true }: { correct: boolean; answered?: boolean }) {
  const label = !answered ? "未回答" : correct ? "正解" : "不正解";
  const color = !answered ? colors.muted : correct ? correctColor : wrongColor;
  return <Text style={[s.verdict, { color }]}>{label}</Text>;
}

/** 出題順の何問目か */
export function Eyebrow({ children }: { children: React.ReactNode }) {
  return <Text style={s.eyebrow}>{children}</Text>;
}

/** AI が公式の発表から作ったクイズに、元の発表へのリンクを添える（ADR-0031。web の `AiSourceNote`） */
export function SourceNote({ sourceUrl }: { sourceUrl?: string | null }) {
  if (!sourceUrl || !/^https?:/i.test(sourceUrl)) return null;
  return (
    <Text style={styles.muted}>
      AI が公式の発表をもとに作り、管理者が確かめて公開した問題です。
      <Text
        accessibilityRole="link"
        style={{ textDecorationLine: "underline" }}
        onPress={() => void WebBrowser.openBrowserAsync(sourceUrl)}
      >
        元の発表
      </Text>
    </Text>
  );
}

const s = StyleSheet.create({
  options: { gap: 10 },
  card: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 14,
    backgroundColor: colors.background,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  cardBody: { flex: 1, gap: 2 },
  selected: { borderColor: colors.text },
  correct: { borderColor: correctColor, backgroundColor: "#ecfdf5" },
  wrong: { borderColor: wrongColor, backgroundColor: "#fef2f2" },
  faded: { opacity: 0.45 },
  radio: { width: 18, height: 18, borderRadius: 9, borderWidth: 1.5, borderColor: colors.muted },
  radioChecked: { borderWidth: 5, borderColor: colors.text },
  letter: {
    width: 30,
    height: 30,
    borderRadius: 15,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: colors.surface,
  },
  letterSelected: { backgroundColor: colors.text },
  letterText: { fontSize: 13, fontWeight: "600", color: colors.muted },
  letterTextOn: { color: "#ffffff" },
  verdict: { fontSize: 14, fontWeight: "700" },
  eyebrow: { fontSize: 12, fontWeight: "600", letterSpacing: 1, color: colors.muted },
});
