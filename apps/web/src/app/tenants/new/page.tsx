import { TenantCreation } from "@/components/tenant/tenant-creation";

/**
 * テナントの作成（ADR-0028）。テナントの外に置く。作る人は、まだそのテナントに所属していない。
 * ログインしていなければ、proxy がログインへ移し、ログインのあとにここへ戻す
 */
export default function NewTenantPage() {
  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <TenantCreation />
    </main>
  );
}
