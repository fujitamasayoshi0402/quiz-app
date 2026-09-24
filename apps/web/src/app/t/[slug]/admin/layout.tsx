import { AdminNav } from "@/components/admin/admin-nav";

/**
 * 管理画面。**権限の判定はバックエンドが行う。** 管理者でなければ API が 403 を返し、各画面がそれを表示する。
 * 画面側で隠しても、API を直接呼べば同じことができるため、ここでは判定しない。
 */
export default async function AdminLayout({ children, params }: LayoutProps<"/t/[slug]/admin">) {
  const { slug } = await params;
  return (
    <div className="space-y-6">
      <AdminNav slug={slug} />
      {children}
    </div>
  );
}
