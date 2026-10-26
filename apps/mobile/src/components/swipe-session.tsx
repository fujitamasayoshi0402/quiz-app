import { Stack, useRouter } from "expo-router";
import { useCallback, useRef, useState } from "react";
import { FlatList, Modal, Pressable, ScrollView, Text, View, type ViewToken } from "react-native";
import { AttemptEnded, ENDED_ELSEWHERE } from "@/components/attempt-ended";
import { Markdown } from "@/components/markdown";
import { ChoiceList, Eyebrow, SourceNote, Verdict } from "@/components/play";
import { Button, ErrorMessage, colors, styles } from "@/components/ui";
import { useAnswerQuiz } from "@/lib/api/generated/endpoints";
import type { AnswerResult, AttemptView, DeliveredQuiz } from "@/lib/api/generated/model";
import { useEndedElsewhere } from "@/lib/use-ended-elsewhere";

type Answered = { choiceId: string; result?: AnswerResult; error?: unknown };

/** 最後のページ。並べるものは、問題のほかにこれが 1 つある */
const FINISHED = "finished";
type Page = DeliveredQuiz | typeof FINISHED;

/**
 * テンポモード（DEV-203。web の `SwipeSession`）。1 画面に 1 問を並べ、縦にスワイプして次々に解く。
 *
 * - スワイプは `FlatList` の `pagingEnabled` に任せる。指の動きを自分では組まない
 * - 選択肢を押したら、その場で回答して正誤を出す。解説は冒頭だけを出し、押すと全文を開く
 * - 答えずにスワイプすれば飛ばす。飛ばした問題は未回答のまま、結果画面に出る
 * - 出題は挑戦を始めたときに全問届いているため、次の問題を先に取る必要はない
 */
export function SwipeSession({ slug, attempt }: { slug: string; attempt: AttemptView }) {
  const router = useRouter();
  const list = useRef<FlatList<Page>>(null);

  // 開いたときに未回答だったものだけを並べる。再開したときは、続きから始まる
  const [quizzes] = useState(() => attempt.quizzes.filter((quiz) => !attempt.answeredQuizIds.includes(quiz.id)));
  const pages: Page[] = [...quizzes, FINISHED];
  const alreadyAnswered = attempt.quizzes.length - quizzes.length;
  const total = attempt.quizzes.length;

  const [answers, setAnswers] = useState<Record<string, Answered>>({});
  const [current, setCurrent] = useState(0);
  // 1 ページの高さ。画面の大きさは端末ごとに違うため、並べる前に測る
  const [pageHeight, setPageHeight] = useState(0);
  const answer = useAnswerQuiz();
  const endedElsewhere = useEndedElsewhere(slug, attempt.id);

  const answeredCount = alreadyAnswered + Object.values(answers).filter((a) => a.result).length;
  const correctCount = Object.values(answers).filter((a) => a.result?.isCorrect).length;
  // 最後に答えた問題だけでなく、前のページで送信中の回答も待つ
  const pendingCount = Object.values(answers).filter((a) => !a.result && a.error === undefined).length;

  const choose = (quiz: DeliveredQuiz, choiceId: string) => {
    const previous = answers[quiz.id];
    if (previous && previous.error === undefined) return;
    setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId } }));
    // mutate に渡したコールバックは、最後の呼び出しの分しか呼ばれない。続けて答えたときに取りこぼさないよう、Promise で受ける
    answer
      .mutateAsync({ slug, attemptId: attempt.id, data: { quizId: quiz.id, choiceId } })
      .then((result) => setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId, result } })))
      .catch((error: unknown) => {
        setAnswers((prev) => ({ ...prev, [quiz.id]: { choiceId, error } }));
        void endedElsewhere.check(error);
      });
  };

  const scrollTo = (index: number) => list.current?.scrollToIndex({ index, animated: true });

  // 画面の大半に出ている問題を「いまの問題」にする
  // FlatList は、この関数が描画のたびに替わることを受け付けない。変わらない関数にする
  const onViewableItemsChanged = useCallback(({ viewableItems }: { viewableItems: ViewToken[] }) => {
    const index = viewableItems[0]?.index;
    if (index != null) setCurrent(index);
  }, []);

  const getItemLayout = useCallback(
    (_: unknown, index: number) => ({ length: pageHeight, offset: pageHeight * index, index }),
    [pageHeight],
  );

  if (endedElsewhere.ended) return <AttemptEnded slug={slug} message={ENDED_ELSEWHERE} />;

  return (
    <View
      style={{ flex: 1, backgroundColor: colors.background }}
      onLayout={(e) => setPageHeight(e.nativeEvent.layout.height)}
    >
      <Stack.Screen options={{ title: `${Math.min(alreadyAnswered + current + 1, total)} / ${total} 問` }} />
      {pageHeight > 0 && (
        <FlatList
          ref={list}
          data={pages}
          keyExtractor={(item) => (item === FINISHED ? FINISHED : item.id)}
          pagingEnabled
          showsVerticalScrollIndicator={false}
          getItemLayout={getItemLayout}
          onViewableItemsChanged={onViewableItemsChanged}
          viewabilityConfig={{ itemVisiblePercentThreshold: 60 }}
          renderItem={({ item, index }) => (
            <View style={{ height: pageHeight }}>
              {item === FINISHED ? (
                <Finished
                  answeredCount={answeredCount}
                  correctCount={correctCount}
                  total={total}
                  pendingCount={pendingCount}
                  onResult={() =>
                    router.replace({
                      pathname: "/t/[slug]/play/result",
                      params: { slug, attempt: attempt.id, mode: "swipe" },
                    })
                  }
                />
              ) : (
                <QuizPage
                  slug={slug}
                  number={alreadyAnswered + index + 1}
                  quiz={item}
                  answered={answers[item.id]}
                  onChoose={(choiceId) => choose(item, choiceId)}
                  onNext={() => scrollTo(index + 1)}
                />
              )}
            </View>
          )}
        />
      )}
    </View>
  );
}

function QuizPage({
  slug,
  number,
  quiz,
  answered,
  onChoose,
  onNext,
}: {
  slug: string;
  number: number;
  quiz: DeliveredQuiz;
  answered?: Answered;
  onChoose: (choiceId: string) => void;
  onNext: () => void;
}) {
  const [explanationOpen, setExplanationOpen] = useState(false);
  const result = answered?.result;
  // 押したらすぐに回答する。結果が届くまでは押し直せない。失敗したときは選び直せる
  const locked = answered !== undefined && answered.error === undefined;

  return (
    <View style={{ flex: 1, padding: 16, gap: 12 }}>
      {/* 問題文と解説の冒頭が長いと、1 画面に収まらない。収まらない分は、ページの中で送る */}
      <ScrollView contentContainerStyle={{ gap: 20, paddingTop: 8 }} nestedScrollEnabled>
        <View style={{ gap: 8 }}>
          <Eyebrow>QUESTION {number}・テンポモード</Eyebrow>
          <Text style={styles.question}>{quiz.question}</Text>
        </View>

        <ChoiceList
          choices={quiz.choices}
          selectedId={answered?.choiceId}
          onSelect={locked ? undefined : onChoose}
          reveal={result ? { correctChoiceId: result.correctChoiceId } : undefined}
        />

        {answered?.error !== undefined && <ErrorMessage error={answered.error} />}
        {result && (
          <Pressable
            accessibilityRole="button"
            accessibilityHint="解説の全文を開きます"
            onPress={() => setExplanationOpen(true)}
            style={({ pressed }) => [styles.panel, pressed && styles.dimmed]}
          >
            <View style={{ flexDirection: "row", alignItems: "center" }}>
              <Verdict correct={result.isCorrect} />
              <Text style={[styles.muted, { marginLeft: "auto" }]}>解説を読む ›</Text>
            </View>
            {/* 冒頭だけを出す。テンポを保ちつつ、読みたい人は全文を開ける。中の図やリンクは押させない */}
            <View pointerEvents="none" style={{ maxHeight: 72, overflow: "hidden" }}>
              <Markdown tenant={slug}>{result.explanation}</Markdown>
            </View>
          </Pressable>
        )}
      </ScrollView>

      <Button label={answered ? "次へ" : "飛ばす"} variant={result ? "primary" : "secondary"} onPress={onNext} />

      {result && (
        <Modal
          visible={explanationOpen}
          animationType="slide"
          presentationStyle="pageSheet"
          onRequestClose={() => setExplanationOpen(false)}
        >
          <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
            <View style={{ flexDirection: "row", alignItems: "center" }}>
              <Text accessibilityRole="header" style={styles.title}>
                解説
              </Text>
              <Text
                accessibilityRole="button"
                onPress={() => setExplanationOpen(false)}
                style={[styles.text, { marginLeft: "auto", color: colors.primary }]}
              >
                閉じる
              </Text>
            </View>
            <Markdown tenant={slug}>{result.explanation}</Markdown>
            <SourceNote sourceUrl={result.sourceUrl} />
          </ScrollView>
        </Modal>
      )}
    </View>
  );
}

function Finished({
  answeredCount,
  correctCount,
  total,
  pendingCount,
  onResult,
}: {
  answeredCount: number;
  correctCount: number;
  total: number;
  pendingCount: number;
  onResult: () => void;
}) {
  return (
    <View style={{ flex: 1, justifyContent: "center", alignItems: "center", gap: 20, padding: 24 }}>
      <Eyebrow>FINISHED</Eyebrow>
      <Text style={styles.title}>おつかれさまでした</Text>
      <View style={{ flexDirection: "row", gap: 12, alignSelf: "stretch" }}>
        <View style={[styles.panel, { flex: 1, gap: 4 }]}>
          <Text style={styles.muted}>回答</Text>
          <Text style={styles.title}>
            {answeredCount} / {total}
          </Text>
        </View>
        <View style={[styles.panel, { flex: 1, gap: 4 }]}>
          <Text style={styles.muted}>今回の正解</Text>
          <Text style={styles.title}>{correctCount}</Text>
        </View>
      </View>
      {pendingCount === 0 && answeredCount < total && (
        <Text style={styles.muted}>飛ばした {total - answeredCount} 問は、未回答として結果に出ます</Text>
      )}
      <View style={{ alignSelf: "stretch" }}>
        <Button
          label={pendingCount > 0 ? `回答を送信しています（残り ${pendingCount} 問）…` : "結果を見る"}
          onPress={onResult}
          disabled={pendingCount > 0}
        />
      </View>
    </View>
  );
}
