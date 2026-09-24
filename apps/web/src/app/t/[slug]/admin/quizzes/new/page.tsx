import { NewQuiz } from "@/components/admin/quiz-editor";

export default async function NewQuizPage({ params }: PageProps<"/t/[slug]/admin/quizzes/new">) {
  const { slug } = await params;
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">クイズを作る</h1>
      <NewQuiz slug={slug} />
    </div>
  );
}
