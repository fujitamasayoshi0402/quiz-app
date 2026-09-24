import { redirect } from "next/navigation";

/** テナントのトップ。Phase 1 では出題条件の選択へ進めるだけ */
export default async function TenantTop({ params }: PageProps<"/t/[slug]">) {
  const { slug } = await params;
  redirect(`/t/${slug}/play`);
}
