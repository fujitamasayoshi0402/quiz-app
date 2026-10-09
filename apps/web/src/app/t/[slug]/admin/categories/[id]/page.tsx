import { CategoryEditor } from "@/components/admin/category-editor";

export default async function CategoryPage({ params }: PageProps<"/t/[slug]/admin/categories/[id]">) {
  const { slug, id } = await params;
  return <CategoryEditor slug={slug} categoryId={id} />;
}
