import { useQueryClient } from "@tanstack/react-query";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useRef, useState } from "react";
import { AccessibilityInfo, type LayoutChangeEvent, ScrollView, Text, View } from "react-native";
import { OptionGroup } from "@/components/play";
import { ResumeCard } from "@/components/resume-card";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { ApiError } from "@/lib/api/fetcher";
import {
  getGetCurrentAttemptQueryKey,
  useListPlayableCategories,
  useStartAttempt,
} from "@/lib/api/generated/endpoints";
import { type FeedbackMode, type Option, parseMode, rangeOptions, toCriteria } from "@/lib/play";
import { saveMode } from "@/lib/play-mode";

const ORDERS: Option[] = [
  { value: "random", label: "ランダム" },
  { value: "registered", label: "登録順", hint: "レベルの低い順" },
];

/** 画面の初期値は 10 問（要件定義）。「すべて」はサーバーの上限まで出す */
const LIMITS: Option[] = [
  { value: "5", label: "5 問" },
  { value: "10", label: "10 問" },
  { value: "20", label: "20 問" },
  { value: "all", label: "すべて" },
];

const MODES: Option[] = [
  { value: "learn", label: "学習モード", hint: "1 問ごとに正誤と解説を見る" },
  { value: "exam", label: "模試モード", hint: "最後にまとめて答え合わせする" },
  { value: "swipe", label: "テンポモード", hint: "縦にスワイプして次々に解く" },
];

/**
 * 出題条件の選択（web の `PlaySetup`）。カテゴリ → 範囲 → 並び・出題数 → 答え合わせの方式の順に選ぶ。
 * 出題対象（間違えた問題、あとで見直す問題など）は、アプリではまだ選べない。すべての問題から出す
 */
export default function PlaySetup() {
  const params = useLocalSearchParams<{ slug: string; category?: string }>();
  const slug = params.slug;
  const router = useRouter();
  const queryClient = useQueryClient();
  const categories = useListPlayableCategories(slug);

  const [categoryId, setCategoryId] = useState(params.category);
  const [rangeValue, setRangeValue] = useState("all");
  const [order, setOrder] = useState("random");
  const [limit, setLimit] = useState("10");
  const [mode, setMode] = useState<FeedbackMode>("learn");

  // カテゴリを最初に選んだら、その下に出る「範囲」まで送る（DEV-213。web と同じ）。選んでも何も起きないように見えるため
  const scroll = useRef<ScrollView>(null);
  const viewport = useRef({ offset: 0, height: 0 });
  const revealRange = useRef(false);
  const onRangeLayout = (event: LayoutChangeEvent) => {
    if (!revealRange.current) return;
    revealRange.current = false;
    const { y, height } = event.nativeEvent.layout;
    // すでに見えていれば動かさない
    if (y + height <= viewport.current.offset + viewport.current.height) return;
    void AccessibilityInfo.isReduceMotionEnabled().then((reduceMotion) =>
      scroll.current?.scrollTo({ y: Math.max(0, y - 16), animated: !reduceMotion }),
    );
  };

  const start = useStartAttempt({
    mutation: {
      onSuccess: async (attempt) => {
        await saveMode(attempt.id, mode);
        queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
        router.push({ pathname: "/t/[slug]/play/session", params: { slug, attempt: attempt.id, mode } });
      },
    },
  });

  if (categories.isPending) return <Loading />;
  if (categories.isError) {
    return (
      <Screen>
        <ErrorMessage error={categories.error} />
        <Button label="もう一度読み込む" onPress={() => void categories.refetch()} />
      </Screen>
    );
  }
  if (categories.data.length === 0) {
    return (
      <Screen>
        <Text style={styles.text}>
          出題できるクイズがありません。管理者がクイズを公開すると、ここから解けるようになります。
        </Text>
      </Screen>
    );
  }

  const category = categories.data.find((c) => c.id === categoryId);
  const ranges = category ? rangeOptions(category) : [];
  const range = ranges.find((r) => r.value === rangeValue) ?? ranges[0];

  const submit = (discardInProgress = false) => {
    if (!category || !range) return;
    start.mutate({
      slug,
      data: {
        ...toCriteria(category.id, range.range),
        order,
        limit: limit === "all" ? undefined : Number(limit),
        discardInProgress,
      },
    });
  };

  // 中断中の挑戦があると 409 が返る。黙って破棄せず、破棄してよいかを確かめる
  const conflict = start.error instanceof ApiError && start.error.status === 409;

  return (
    <ScrollView
      ref={scroll}
      style={styles.scroll}
      contentContainerStyle={styles.scrollContent}
      onLayout={(event) => (viewport.current.height = event.nativeEvent.layout.height)}
      onScroll={(event) => (viewport.current.offset = event.nativeEvent.contentOffset.y)}
      scrollEventThrottle={100}
    >
      <ResumeCard slug={slug} />
      <Section title="カテゴリ">
        <OptionGroup
          value={categoryId}
          onChange={(value) => {
            // 選び直したときは動かさない
            if (!categoryId) revealRange.current = true;
            setCategoryId(value);
            setRangeValue("all");
          }}
          options={categories.data.map((c) => ({
            value: c.id,
            label: c.name,
            hint: c.description ? `${c.description}・${c.quizCount} 問` : `${c.quizCount} 問`,
          }))}
        />
      </Section>

      {category && (
        <>
          <Section title="範囲" onLayout={onRangeLayout}>
            <OptionGroup value={range?.value} onChange={setRangeValue} options={ranges} />
          </Section>
          <Section title="並び">
            <OptionGroup value={order} onChange={setOrder} options={ORDERS} />
          </Section>
          <Section title="出題数">
            <OptionGroup value={limit} onChange={setLimit} options={LIMITS} />
          </Section>
          <Section title="答え合わせ">
            <OptionGroup value={mode} onChange={(value) => setMode(parseMode(value))} options={MODES} />
          </Section>

          {conflict ? (
            <View style={styles.notice}>
              <Text style={[styles.text, { fontWeight: "600" }]}>中断中のクイズがあります</Text>
              <Text style={styles.muted}>新しく始めると、中断中のクイズは破棄されます。</Text>
              <Button label="破棄して新しく始める" onPress={() => submit(true)} disabled={start.isPending} />
              <Button label="やめる" variant="secondary" onPress={() => start.reset()} />
            </View>
          ) : (
            <>
              {start.isError && <ErrorMessage error={start.error} />}
              <Button
                label={start.isPending ? "準備しています…" : "始める"}
                onPress={() => submit()}
                disabled={start.isPending}
              />
            </>
          )}
        </>
      )}
    </ScrollView>
  );
}

function Section({
  title,
  children,
  onLayout,
}: {
  title: string;
  children: React.ReactNode;
  onLayout?: (event: LayoutChangeEvent) => void;
}) {
  return (
    <View style={{ gap: 10 }} onLayout={onLayout}>
      <Text accessibilityRole="header" style={[styles.text, { fontWeight: "600" }]}>
        {title}
      </Text>
      {children}
    </View>
  );
}
