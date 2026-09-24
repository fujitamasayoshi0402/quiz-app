import Link from "next/link";
import { cookies } from "next/headers";
import { USER_COOKIE, findStubUser } from "@/lib/auth/stub-users";

export default async function TenantLayout({ children, params }: LayoutProps<"/t/[slug]">) {
  const { slug } = await params;
  const user = findStubUser((await cookies()).get(USER_COOKIE)?.value);

  return (
    <div className="flex min-h-svh flex-col">
      <header className="bg-background border-b">
        <div className="mx-auto flex h-14 max-w-2xl items-center justify-between px-4">
          <Link href={`/t/${slug}/play`} className="font-semibold">
            Quiz <span className="text-muted-foreground text-sm font-normal">/ {slug}</span>
          </Link>
          <div className="flex items-center gap-3 text-sm">
            <span className="text-muted-foreground">{user?.name ?? "未選択"}</span>
            <Link href="/" className="underline-offset-4 hover:underline">
              切り替える
            </Link>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8">{children}</main>
    </div>
  );
}
