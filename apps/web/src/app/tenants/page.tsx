import { PublicTenantList } from "@/components/tenant/public-tenant-list";

/**
 * 公開テナントの一覧（ADR-0025）。テナントの外に置く。参加する人は、まだそのテナントに所属していない。
 * ログインしていなければ、proxy がログインへ移し、ログインのあとにここへ戻す
 */
export default function PublicTenantsPage() {
  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <PublicTenantList />
    </main>
  );
}
