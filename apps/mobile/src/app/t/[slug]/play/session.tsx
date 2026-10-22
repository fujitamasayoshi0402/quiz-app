import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { useRef, useState } from "react";
import { ScrollView, Text, View } from "react-native";
import { Markdown } from "@/components/markdown";
import { ChoiceList, Eyebrow, SourceNote, Verdict } from "@/components/play";
import { SwipeSession } from "@/components/swipe-session";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { ApiError } from "@/lib/api/fetcher";
import { useAnswerQuiz, useResumeAttempt } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView } from "@/lib/api/generated/model";
import { type FeedbackMode, parseMode } from "@/lib/play";

/**
 * 回答画面（web の `PlaySession`）。挑戦をサーバーから読み直して始めるため、別の端末で始めた挑戦でも同じ位置に戻る。
 * **現在位置は保存しない。** 出題順と回答済みの一覧から、最初の未回答を導く（要件定義）
 */
export default function PlaySession() {
  const params = useLocalSearchParams<{ slug: string; attempt: string; mode?: string }>();
  // 画面を離れたらキャッシュを捨てる（gcTime: 0）。残すと、次に開いたとき、最初に開いたときの回答済みの一覧から始まる
  const attempt = useResumeAttempt(params.slug, params.attempt, { query: { staleTime: Infinity, gcTime: 0 } });

  if (attempt.isPending) return <Loading />;
  if (attempt.isError) {
    return (
      <Screen>
        <ErrorMessage error={attempt.error} />
      </Screen>
    );
  }
  const mode = parseMode(params.mode);
  if (mode === "swipe" && attempt.data.status === "in_progress") {
    return <SwipeSession slug={params.slug} attempt={attempt.data} />;
  }
  return <Session slug={params.slug} attempt={attempt.data} mode={mode} />;
}

function Session({ slug, attempt, mode }: { slug: string; attempt: AttemptView; mode: FeedbackMode }) {
  const router = useRouter();
  const scroll = useRef<ScrollView>(null);
  const [done, setDone] = useState(() => new Set(attempt.answeredQuizIds));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<AnswerResult | null>(null);

  const answer = useAnswerQuiz();

  const total = attempt.quizzes.length;
  const current = attempt.quizzes.find((quiz) => !done.has(quiz.id));
  const position = attempt.quizzes.findIndex((quiz) => quiz.id === current?.id) + 1;
  const showResult = () =>
    router.replace({ pathname: "/t/[slug]/play/result", params: { slug, attempt: attempt.id, mode } });

  const advance = (quizId: string) => {
    const next = new Set(done).add(quizId);
    setDone(next);
    setSelectedId(null);
    setFeedback(null);
    answer.reset();
    if (next.size >= total) showResult();
    else scroll.current?.scrollTo({ y: 0, animated: false });
  };

  const submit = () => {
    if (!current || !selectedId) return;
    answer.mutate(
      { slug, attemptId: attempt.id, data: { quizId: current.id, choiceId: selectedId } },
      {
        // 学習モードはその場で答え合わせをする。模試モードは正誤を伏せて次へ進む
        onSuccess: (result) => (mode === "learn" ? setFeedback(result) : advance(current.id)),
      },
    );
  };

  if (attempt.status !== "in_progress" || !current) {
    return (
      <Screen>
        <Text style={styles.text}>このクイズは終了しています。</Text>
        <Button label="結果を見る" onPress={showResult} />
      </Screen>
    );
  }

  // 出題後に削除・非公開になったクイズ（409）は、答えられないので飛ばせるようにする
  const unanswerable = answer.error instanceof ApiError && answer.error.status === 409;

  return (
    <ScrollView ref={scroll} style={styles.scroll} contentContainerStyle={styles.scrollContent}>
      <Stack.Screen options={{ title: `${position} / ${total} 問` }} />
      {attempt.excludedCount > 0 && (
        <Text style={styles.muted}>出題後に削除・非公開になった {attempt.excludedCount} 問を除いています。</Text>
      )}

      <View style={{ gap: 8 }}>
        <Eyebrow>
          QUESTION {position}・{mode === "learn" ? "学習モード" : "模試モード"}
        </Eyebrow>
        <Text style={styles.question}>{current.question}</Text>
      </View>

      <ChoiceList
        choices={current.choices}
        selectedId={selectedId}
        onSelect={setSelectedId}
        reveal={feedback ? { correctChoiceId: feedback.correctChoiceId } : undefined}
      />

      {feedback && (
        <View style={styles.panel}>
          <Verdict correct={feedback.isCorrect} />
          <Markdown tenant={slug}>{feedback.explanation}</Markdown>
          <SourceNote sourceUrl={feedback.sourceUrl} />
        </View>
      )}
      {answer.isError && <ErrorMessage error={answer.error} />}

      {feedback || unanswerable ? (
        <Button label={done.size + 1 >= total ? "結果を見る" : "次の問題へ"} onPress={() => advance(current.id)} />
      ) : (
        <Button label="回答する" onPress={submit} disabled={!selectedId || answer.isPending} />
      )}
    </ScrollView>
  );
}
