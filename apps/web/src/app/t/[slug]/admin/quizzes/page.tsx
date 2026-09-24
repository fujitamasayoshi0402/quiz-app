import { QuizList } from "@/components/admin/quiz-list";

export default async function QuizzesPage({ params }: PageProps<"/t/[slug]/admin/quizzes">) {
  const { slug } = await params;
  return <QuizList slug={slug} />;
}
