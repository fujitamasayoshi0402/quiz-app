import { useInfiniteQuery } from "@tanstack/react-query";
import { useLocalSearchParams, useRouter } from "expo-router";
import { Pressable, ScrollView, Text, View } from "react-native";
import { Button, ErrorMessage, Loading, colors, styles } from "@/components/ui";
import {
  getListCompletedAttemptsQueryKey,
  listCompletedAttempts,
  useListCategoryScores,
} from "@/lib/api/generated/endpoints";
import type { AttemptHistoryItem, CategoryScore } from "@/lib/api/generated/model";
import { attemptRate, attemptTitle, rateOf, scopeLabel } from "@/lib/history";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * 履歴（web の `/t/{slug}/history`）。カテゴリ別の正答率と、最後まで解いたクイズの一覧。
 * web にある学習の記録（連続日数とカレンダー）は、アプリにはまだない
 */
export default function History() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
      <View style={{ gap: 12 }}>
        <Text accessibilityRole="header" style={[styles.text, { fontWeight: "600" }]}>
          カテゴリ別の正答率
        </Text>
        <CategoryScores slug={slug} />
      </View>
      <View style={{ gap: 12 }}>
        <Text accessibilityRole="header" style={[styles.text, { fontWeight: "600" }]}>
          解いたクイズ
        </Text>
        <AttemptHistory slug={slug} />
      </View>
    </ScrollView>
  );
}

/**
 * カテゴリ別の正答率。数えるのは、いま出題できるクイズへの最新の回答だけ（バックエンドが決める）。
 * 解いていないカテゴリも並べる。どこに手を付けていないかも、次に解くものを選ぶ手がかりになる
 */
function CategoryScores({ slug }: { slug: string }) {
  const scores = useListCategoryScores(slug);

  if (scores.isPending) return <Loading />;
  if (scores.isError) return <ErrorMessage error={scores.error} />;
  if (scores.data.length === 0) return <Text style={styles.muted}>出題できるクイズがまだありません。</Text>;
  return (
    <View>
      {scores.data.map((score) => (
        <ScoreRow key={score.categoryId} score={score} />
      ))}
    </View>
  );
}

function ScoreRow({ score: { name, quizCount, answeredCount, correctCount } }: { score: CategoryScore }) {
  const rate = rateOf(answeredCount, correctCount);
  const progress = quizCount === 0 ? 0 : answeredCount / quizCount;
  return (
    <View style={[styles.row, { gap: 6 }]}>
      <View style={{ flexDirection: "row", alignItems: "baseline", gap: 12 }}>
        <Text style={[styles.text, { flex: 1, fontWeight: "600" }]}>{name}</Text>
        <Text style={rate == null ? styles.muted : [styles.text, { fontWeight: "700" }]}>
          {answeredCount === 0 ? "—" : rate == null ? "回答が少ない" : `${rate}%`}
        </Text>
      </View>
      {/* 棒は解いた割合。正答率は数字で出す。手を付けていない問題がどれだけ残っているかを見せる */}
      <View
        accessibilityRole="progressbar"
        accessibilityLabel={`${name} の解いた割合`}
        accessibilityValue={{ min: 0, max: quizCount, now: answeredCount }}
        style={{ height: 6, borderRadius: 3, backgroundColor: colors.surface, overflow: "hidden" }}
      >
        <View style={{ width: `${progress * 100}%`, height: "100%", backgroundColor: colors.primary }} />
      </View>
      <Text style={styles.muted}>
        {answeredCount === 0
          ? `${quizCount} 問。まだ解いていません`
          : `${quizCount} 問中 ${answeredCount} 問を解いて ${correctCount} 問正解`}
      </Text>
    </View>
  );
}

/**
 * 完了した挑戦の一覧。新しい順に、続きは「もっと見る」で読む。
 * 各行から結果画面を開く。振り返りのために開くので、学習モードでも全問の解説を出す（模試モード）
 */
function AttemptHistory({ slug }: { slug: string }) {
  const router = useRouter();
  const history = useInfiniteQuery({
    // 生成したフックと同じキーにすると、ページを持たない形のキャッシュと取り違える
    queryKey: [...getListCompletedAttemptsQueryKey(slug), "infinite"],
    queryFn: ({ pageParam, signal }) =>
      listCompletedAttempts(slug, pageParam ? { cursor: pageParam } : undefined, { signal }),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
  });

  if (history.isPending) return <Loading />;
  if (history.isLoadingError) return <ErrorMessage error={history.error} />;

  const items = history.data.pages.flatMap((page) => page.items);
  if (items.length === 0) return <Text style={styles.muted}>まだ最後まで解いたクイズがありません。</Text>;

  return (
    <View style={{ gap: 12 }}>
      <View>
        {items.map((item) => (
          <AttemptRow
            key={item.id}
            item={item}
            onPress={() =>
              router.push({
                pathname: "/t/[slug]/play/result",
                params: { slug, attempt: item.id, mode: "exam", from: "history" },
              })
            }
          />
        ))}
      </View>
      {/* 続きの読み込みに失敗しても、読めた分は残す */}
      {history.isRefetchError && <ErrorMessage error={history.error} />}
      {history.hasNextPage && (
        <Button
          label={history.isFetchingNextPage ? "読み込み中…" : "もっと見る"}
          variant="secondary"
          disabled={history.isFetchingNextPage}
          onPress={() => void history.fetchNextPage()}
        />
      )}
    </View>
  );
}

function AttemptRow({ item, onPress }: { item: AttemptHistoryItem; onPress: () => void }) {
  const { totalCount, answeredCount, correctCount } = item;
  return (
    <Pressable
      accessibilityRole="link"
      onPress={onPress}
      style={({ pressed }) => [styles.row, { flexDirection: "row", gap: 12 }, pressed && styles.dimmed]}
    >
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={[styles.text, { fontWeight: "600" }]}>{attemptTitle(item)}</Text>
        <Text style={styles.muted}>
          {dateFormat.format(new Date(item.finishedAt))}・{scopeLabel(item.scope)}
          {answeredCount < totalCount && `・未回答 ${totalCount - answeredCount} 問`}
        </Text>
      </View>
      <View style={{ alignItems: "flex-end" }}>
        <Text style={[styles.text, { fontWeight: "700" }]}>{attemptRate(item)}%</Text>
        <Text style={styles.muted}>
          {correctCount} / {totalCount}
        </Text>
      </View>
    </Pressable>
  );
}
