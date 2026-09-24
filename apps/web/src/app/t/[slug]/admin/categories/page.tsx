import { CategoryList } from "@/components/admin/category-list";

export default async function CategoriesPage({ params }: PageProps<"/t/[slug]/admin/categories">) {
  const { slug } = await params;
  return <CategoryList slug={slug} />;
}
