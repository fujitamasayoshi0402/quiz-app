"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { ChevronRight } from "lucide-react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { SignOutButton } from "@/components/tenant/sign-out-button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useListMyTenants } from "@/lib/api/generated/endpoints";
import { roleLabel } from "@/lib/auth/roles";

/**
 * 所属テナントの数で出し分ける。
 *
 * | 所属 | 表示 |
 * | --- | --- |
 * | 0 | 招待を受けていない旨 |
 * | 1 | そのテナントへ移る。選択肢が 1 つしかない画面は挟まない |
 * | 2 以上 | 選択画面 |
 *
 * 一覧はブラウザから取る。サーバーで取ると、利用者の識別を proxy 以外でも付けることになる。
 */
export function TenantPicker() {
  const router = useRouter();
  const tenants = useListMyTenants();
  const only = tenants.data?.length === 1 ? tenants.data[0] : undefined;

  useEffect(() => {
    if (only) router.replace(`/t/${only.slug}`);
  }, [only, router]);

  if (tenants.isPending || only) return <Skeleton className="h-40 w-full" />;
  if (tenants.isError) return <ApiErrorAlert error={tenants.error} />;

  if (tenants.data.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>所属しているテナントがありません</CardTitle>
          <CardDescription>
            管理者から招待のリンクを受け取ったら、そのリンクを開いてください。参加すると、ここから入れるようになります。
          </CardDescription>
        </CardHeader>
        <CardContent>
          <SignOutButton />
        </CardContent>
      </Card>
    );
  }

  return (
    <>
      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-semibold">テナントを選ぶ</h1>
        <p className="text-muted-foreground text-sm">所属しているテナントから選んでください</p>
      </div>
      <ul className="space-y-3">
        {tenants.data.map((tenant) => (
          <li key={tenant.slug}>
            <Link href={`/t/${tenant.slug}`} className="block">
              <Card className="hover:bg-accent transition-colors">
                <CardHeader className="flex items-center justify-between">
                  <div className="space-y-1">
                    <CardTitle>{tenant.name}</CardTitle>
                    <CardDescription>/t/{tenant.slug}</CardDescription>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant="secondary">{roleLabel(tenant.role)}</Badge>
                    <ChevronRight className="text-muted-foreground size-4" />
                  </div>
                </CardHeader>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
      <SignOutButton className="text-center" />
    </>
  );
}
