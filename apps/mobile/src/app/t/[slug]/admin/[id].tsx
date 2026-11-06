import { useLocalSearchParams } from "expo-router";
import { EditQuiz } from "@/components/admin/quiz-editor";

/** クイズを直す（DEV-227） */
export default function AdminEditQuiz() {
  const { slug, id } = useLocalSearchParams<{ slug: string; id: string }>();
  return <EditQuiz slug={slug} quizId={id} />;
}
