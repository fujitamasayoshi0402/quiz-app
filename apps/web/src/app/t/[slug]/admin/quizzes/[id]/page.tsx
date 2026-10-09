import { EditQuiz } from "@/components/admin/quiz-editor";

export default async function EditQuizPage({ params }: PageProps<"/t/[slug]/admin/quizzes/[id]">) {
  const { slug, id } = await params;
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">クイズを編集する</h1>
      <EditQuiz slug={slug} quizId={id} />
    </div>
  );
}
