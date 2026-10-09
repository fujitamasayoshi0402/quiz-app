import { QuizImport } from "@/components/admin/quiz-import";

export default async function ImportQuizzesPage({ params }: PageProps<"/t/[slug]/admin/quizzes/import">) {
  const { slug } = await params;
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">クイズをまとめて取り込む</h1>
      <QuizImport slug={slug} />
    </div>
  );
}
