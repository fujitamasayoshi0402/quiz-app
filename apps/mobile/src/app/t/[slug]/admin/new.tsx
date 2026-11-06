import { useLocalSearchParams } from "expo-router";
import { NewQuiz } from "@/components/admin/quiz-editor";

/** クイズを作る（DEV-227） */
export default function AdminNewQuiz() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  return <NewQuiz slug={slug} />;
}
