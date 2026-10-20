import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useEffect } from "react";
import { ScrollView, Text, View } from "react-native";
import { Markdown } from "@/components/markdown";
import { ChoiceList, Eyebrow, SourceNote, Verdict } from "@/components/play";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { completeAttempt, getGetCurrentAttemptQueryKey } from "@/lib/api/generated/endpoints";
import type { QuizResult } from "@/lib/api/generated/model";
import { parseMode } from "@/lib/play";

/**
 * 結果画面（web の `PlayResult`）。
 *
 * 完了 API は完了済みの挑戦にも結果を返すため、表示のたびに呼んでよい。
 * **学習モードでは間違えた問題だけ、模試モードでは全問の解説を出す。** 模試モードは、ここが唯一の振り返りの場になる
 */
export default function PlayResult() {
  const params = useLocalSearchParams<{ slug: string; attempt: string; mode?: string; from?: string }>();
  const { slug } = params;
  const mode = parseMode(params.mode);
  // 履歴から開いたときは、履歴へ戻る。解き終えたときは、終わった回答の画面へは戻らせない
  const fromHistory = params.from === "history";
  const router = useRouter();
  const queryClient = useQueryClient();
  const result = useQuery({
    queryKey: ["attempt-result", slug, params.attempt],
    queryFn: ({ signal }) => completeAttempt(slug, params.attempt, { signal }),
    staleTime: Infinity,
  });

  // 完了したので、中断中の挑戦としての表示を消す
  useEffect(() => {
    if (result.isSuccess) queryClient.removeQueries({ queryKey: getGetCurrentAttemptQueryKey(slug) });
  }, [result.isSuccess, queryClient, slug]);

  if (result.isPending) return <Loading />;
  if (result.isError) {
    return (
      <Screen>
        <ErrorMessage error={result.error} />
        <Button label="もう一度読み込む" onPress={() => void result.refetch()} />
      </Screen>
    );
  }

  const { totalCount, answeredCount, correctCount, results } = result.data;
  const rate = totalCount === 0 ? 0 : Math.round((correctCount / totalCount) * 100);
  // 番号は出題順のまま出す。絞り込んだ一覧の中で振り直すと、何問目だったかが分からなくなる
  const numbered = results.map((quiz, index) => ({ quiz, number: index + 1 }));
  const reviewed = mode === "exam" ? numbered : numbered.filter(({ quiz }) => !quiz.isCorrect);

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
      {fromHistory && <Stack.Screen options={{ headerBackVisible: true, gestureEnabled: true }} />}
      <View style={{ flexDirection: "row", gap: 12 }}>
        <Stat label="正答率" value={`${rate}%`} />
        <Stat label="正解" value={`${correctCount} / ${totalCount} 問`} />
      </View>
      {answeredCount < totalCount && <Text style={styles.muted}>未回答 {totalCount - answeredCount} 問</Text>}

      <View style={{ gap: 12 }}>
        <Text accessibilityRole="header" style={[styles.text, { fontWeight: "600" }]}>
          {mode === "exam" ? "答え合わせ" : "間違えた問題"}
        </Text>
        {reviewed.length === 0 ? (
          <Text style={styles.muted}>全問正解です。</Text>
        ) : (
          reviewed.map(({ quiz, number }) => <Review key={quiz.quizId} slug={slug} quiz={quiz} number={number} />)
        )}
      </View>

      {fromHistory ? (
        <Button label="履歴に戻る" onPress={() => router.back()} />
      ) : (
        <Button
          label="クイズを選ぶ画面に戻る"
          onPress={() => router.dismissTo({ pathname: "/t/[slug]/play", params: { slug } })}
        />
      )}
    </ScrollView>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={[styles.panel, { flex: 1, gap: 4 }]}>
      <Text style={styles.muted}>{label}</Text>
      <Text style={styles.title}>{value}</Text>
    </View>
  );
}

function Review({ slug, quiz, number }: { slug: string; quiz: QuizResult; number: number }) {
  const answered = quiz.selectedChoiceId != null;
  return (
    <View style={styles.panel}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 12 }}>
        <Eyebrow>QUESTION {number}</Eyebrow>
        <Verdict correct={quiz.isCorrect} answered={answered} />
      </View>
      <Text style={[styles.text, { fontWeight: "600" }]}>{quiz.question}</Text>
      <ChoiceList
        choices={quiz.choices}
        selectedId={quiz.selectedChoiceId}
        reveal={{ correctChoiceId: quiz.correctChoiceId }}
      />
      <Markdown tenant={slug}>{quiz.explanation}</Markdown>
      <SourceNote sourceUrl={quiz.sourceUrl} />
    </View>
  );
}
