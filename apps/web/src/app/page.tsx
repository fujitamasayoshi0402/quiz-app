import { redirect } from "next/navigation";
import { TenantPicker } from "@/components/tenant/tenant-picker";
import { currentUser } from "@/lib/auth/current-user";

/** テナントの選択。所属の数で出し分ける（要件定義「テナント未指定のとき」） */
export default async function Home() {
  if (!(await currentUser())) {
    redirect("/login");
  }
  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <TenantPicker />
    </main>
  );
}
