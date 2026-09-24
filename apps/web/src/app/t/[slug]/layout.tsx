import { redirect } from "next/navigation";
import { SignOutButton } from "@/components/tenant/sign-out-button";
import { TenantNav } from "@/components/tenant/tenant-nav";
import { currentUser } from "@/lib/auth/session";

export default async function TenantLayout({ children, params }: LayoutProps<"/t/[slug]">) {
  const { slug } = await params;
  const user = await currentUser();
  if (!user) {
    redirect("/login");
  }

  return (
    <div className="flex min-h-svh flex-col">
      <header className="bg-background border-b">
        <div className="mx-auto flex h-14 max-w-2xl items-center justify-between gap-4 px-4">
          <TenantNav slug={slug} />
          <div className="flex shrink-0 items-center gap-3 text-sm">
            <span className="text-muted-foreground hidden sm:inline">{user.name}</span>
            <SignOutButton />
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8">{children}</main>
    </div>
  );
}
