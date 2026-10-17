import { useRouter } from "expo-router";
import { Text, View } from "react-native";
import { Button, ErrorMessage, styles } from "@/components/ui";
import { useAbandonAttempt, useGetCurrentAttempt } from "@/lib/api/generated/endpoints";
import { loadMode } from "@/lib/play-mode";

/**
 * 中断中の挑戦があれば、再開か破棄を選べるようにする（web の `ResumeCard`）。
 * 挑戦はサーバーに保存されているため、web や別の端末で始めたものもここに出る
 */
export function ResumeCard({ slug }: { slug: string }) {
  const router = useRouter();
  const current = useGetCurrentAttempt(slug);
  const abandon = useAbandonAttempt({ mutation: { onSuccess: () => void current.refetch() } });

  const attempt = current.data;
  if (!attempt) return null;

  const answered = attempt.answeredQuizIds.length;
  const total = attempt.quizzes.length;

  const resume = async () => {
    const mode = await loadMode(attempt.id);
    router.push({ pathname: "/t/[slug]/play/session", params: { slug, attempt: attempt.id, mode } });
  };

  return (
    <View style={styles.notice}>
      <Text style={[styles.text, { fontWeight: "600" }]}>中断中のクイズがあります</Text>
      <Text style={styles.muted}>
        {total} 問中 {answered} 問回答済み
      </Text>
      {abandon.isError && <ErrorMessage error={abandon.error} />}
      <Button label="続きから解く" onPress={() => void resume()} />
      <Button
        label="破棄する"
        variant="secondary"
        disabled={abandon.isPending}
        onPress={() => abandon.mutate({ slug, attemptId: attempt.id })}
      />
    </View>
  );
}
