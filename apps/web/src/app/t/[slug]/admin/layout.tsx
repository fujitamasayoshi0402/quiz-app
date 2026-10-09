import { AdminGuard } from "@/components/admin/admin-guard";
import { AdminNav } from "@/components/admin/admin-nav";

/**
 * 管理画面。管理者でなければ、入口（`AdminGuard`）で案内を出し、中身は描かない。
 * **権限の判定はバックエンドが行う。** 入口で止めるのは、エラーを並べずに済ませるための表示の都合。
 */
export default async function AdminLayout({ children, params }: LayoutProps<"/t/[slug]/admin">) {
  const { slug } = await params;
  return (
    <AdminGuard slug={slug}>
      <div className="space-y-6">
        <AdminNav slug={slug} />
        {children}
      </div>
    </AdminGuard>
  );
}
