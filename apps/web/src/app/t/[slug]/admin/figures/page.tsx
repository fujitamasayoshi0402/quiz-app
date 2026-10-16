import { FigureList } from "@/components/admin/figure-list";

export default async function FiguresPage({ params }: PageProps<"/t/[slug]/admin/figures">) {
  const { slug } = await params;
  return <FigureList slug={slug} />;
}
