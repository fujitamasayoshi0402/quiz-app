import { TrashList } from "@/components/admin/trash-list";

export default async function TrashPage({ params }: PageProps<"/t/[slug]/admin/trash">) {
  const { slug } = await params;
  return <TrashList slug={slug} />;
}
