import { keepPreviousData } from "@tanstack/react-query";
import { useLocalSearchParams } from "expo-router";
import { useState } from "react";
import { Alert, FlatList, Pressable, Text, View } from "react-native";
import { OptionGroup } from "@/components/play";
import { Button, ErrorMessage, Loading, Screen, colors, styles } from "@/components/ui";
import { useCatalog, useInvalidateTenant } from "@/lib/admin/catalog";
import {
  ALL,
  NO_FILTERS,
  ORDER_OPTIONS,
  ORIGIN_OPTIONS,
  type QuizFilters,
  STATUS_OPTIONS,
  accuracyLabel,
  activeFilterCount,
  sortByAccuracy,
  statusLabel,
  toSearchParams,
} from "@/lib/admin/quiz-list";
import { ApiError } from "@/lib/api/fetcher";
import { useDeleteQuiz, useSearchQuizzes } from "@/lib/api/generated/endpoints";
import type { QuizListItemResponse } from "@/lib/api/generated/model";

/**
 * クイズの一覧（DEV-216、ADR-0037）。カテゴリ・難易度・状態・作成元で絞り込み、正答率の低い順に並べ替えられる。
 * 絞り込みは畳んでおく。電話の幅では、開いたままだと一覧が画面の外に出る
 */
export default function AdminQuizList() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  const [filters, setFilters] = useState<QuizFilters>(NO_FILTERS);
  const [order, setOrder] = useState("registered");
  const [filtersOpen, setFiltersOpen] = useState(false);
  const catalog = useCatalog(slug);
  const invalidate = useInvalidateTenant(slug);
  // 絞り込みを変えても前の一覧を出しておく。読み込みの間に消すと、一覧の上にある絞り込みごと消えて、続けて選べない
  const quizzes = useSearchQuizzes(slug, toSearchParams(filters), { query: { placeholderData: keepPreviousData } });
  const remove = useDeleteQuiz({
    mutation: {
      onSuccess: () => invalidate(),
      onError: (error) =>
        Alert.alert(
          "削除できませんでした",
          error instanceof ApiError && error.problem?.detail ? error.problem.detail : "通信の状態を確かめてください",
        ),
    },
  });

  const confirmDelete = (quiz: QuizListItemResponse) =>
    Alert.alert("このクイズを削除しますか？", `${quiz.question}\n\n削除済み一覧（web）から復活できます。`, [
      { text: "やめる", style: "cancel" },
      { text: "削除する", style: "destructive", onPress: () => remove.mutate({ slug, id: quiz.id }) },
    ]);

  const count = activeFilterCount(filters);
  const rows = order === "accuracy" ? sortByAccuracy(quizzes.data ?? []) : quizzes.data;

  return (
    <Screen>
      <Button
        label={
          filtersOpen ? "絞り込みを閉じる" : count > 0 ? `絞り込み・並び（${count} 件で絞り込み中）` : "絞り込み・並び"
        }
        variant="secondary"
        onPress={() => setFiltersOpen(!filtersOpen)}
      />
      {quizzes.isPending ? (
        <Loading />
      ) : quizzes.isError ? (
        <>
          <ErrorMessage error={quizzes.error} />
          <Button label="もう一度読み込む" onPress={() => void quizzes.refetch()} />
        </>
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(quiz) => quiz.id}
          ListHeaderComponent={
            filtersOpen ? (
              <Filters catalog={catalog} filters={filters} onChange={setFilters} order={order} onOrder={setOrder} />
            ) : undefined
          }
          ListEmptyComponent={<Text style={styles.text}>該当するクイズはありません。</Text>}
          renderItem={({ item }) => (
            <QuizRow
              quiz={item}
              meta={`${catalog.categoryName(item.categoryId)}・${catalog.difficultyName(item.difficultyId)}・${accuracyLabel(item)}`}
              deleting={remove.isPending}
              onDelete={() => confirmDelete(item)}
            />
          )}
        />
      )}
    </Screen>
  );
}

function Filters({
  catalog,
  filters,
  onChange,
  order,
  onOrder,
}: {
  catalog: ReturnType<typeof useCatalog>;
  filters: QuizFilters;
  onChange: (filters: QuizFilters) => void;
  order: string;
  onOrder: (order: string) => void;
}) {
  const categories = [
    { value: ALL, label: "すべてのカテゴリ" },
    ...(catalog.categories.data ?? []).map((c) => ({ value: c.id, label: c.name })),
  ];
  const difficulties = [
    { value: ALL, label: "すべての難易度" },
    ...catalog.difficultiesOf(filters.categoryId).map((d) => ({ value: d.id, label: d.name })),
  ];
  return (
    <View style={{ gap: 16, paddingBottom: 16 }}>
      <FilterSection title="カテゴリ">
        <OptionGroup
          value={filters.categoryId}
          // 難易度はカテゴリ配下なので、カテゴリを変えたら選び直させる
          onChange={(categoryId) => onChange({ ...filters, categoryId, difficultyId: ALL })}
          options={categories}
        />
      </FilterSection>
      {filters.categoryId !== ALL && (
        <FilterSection title="難易度">
          <OptionGroup
            value={filters.difficultyId}
            onChange={(difficultyId) => onChange({ ...filters, difficultyId })}
            options={difficulties}
          />
        </FilterSection>
      )}
      <FilterSection title="状態">
        <OptionGroup
          value={filters.status}
          onChange={(status) => onChange({ ...filters, status })}
          options={STATUS_OPTIONS}
        />
      </FilterSection>
      <FilterSection title="作成元">
        <OptionGroup
          value={filters.origin}
          onChange={(origin) => onChange({ ...filters, origin })}
          options={ORIGIN_OPTIONS}
        />
      </FilterSection>
      <FilterSection title="並び">
        <OptionGroup value={order} onChange={onOrder} options={ORDER_OPTIONS} />
      </FilterSection>
    </View>
  );
}

function FilterSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={{ gap: 8 }}>
      <Text style={[styles.text, { fontWeight: "600" }]}>{title}</Text>
      {children}
    </View>
  );
}

function QuizRow({
  quiz,
  meta,
  deleting,
  onDelete,
}: {
  quiz: QuizListItemResponse;
  meta: string;
  deleting: boolean;
  onDelete: () => void;
}) {
  return (
    <View style={[styles.row, { gap: 6 }]}>
      <Text style={styles.text} numberOfLines={2}>
        {quiz.question}
      </Text>
      <Text style={styles.muted} numberOfLines={1}>
        {meta}
      </Text>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
        <Tag label={statusLabel(quiz.status)} />
        {quiz.sourceUrl && <Tag label="AI" />}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`「${quiz.question}」を削除する`}
          disabled={deleting}
          hitSlop={8}
          onPress={onDelete}
          style={({ pressed }) => [{ marginLeft: "auto" }, (pressed || deleting) && styles.dimmed]}
        >
          <Text style={styles.error}>削除</Text>
        </Pressable>
      </View>
    </View>
  );
}

function Tag({ label }: { label: string }) {
  return (
    <View
      style={{ borderWidth: 1, borderColor: colors.border, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 2 }}
    >
      <Text style={styles.muted}>{label}</Text>
    </View>
  );
}
