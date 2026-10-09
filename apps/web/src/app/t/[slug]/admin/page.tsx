import { redirect } from "next/navigation";

export default async function AdminTop({ params }: PageProps<"/t/[slug]/admin">) {
  const { slug } = await params;
  redirect(`/t/${slug}/admin/quizzes`);
}
