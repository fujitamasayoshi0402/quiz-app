"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { getListMyTenantsQueryKey, useJoinPublicTenant, useListPublicTenants } from "@/lib/api/generated/endpoints";
import type { PublicTenantResponse } from "@/lib/api/generated/model";

/**
 * 公開テナントの一覧（要件定義 U1b、ADR-0025）。参加していないものは「参加する」で、一般ユーザーとして所属する。
 *
 * 一覧を開いただけでは参加させない。参加は状態を変える操作なので、利用者の操作（POST）を待つ
 */
export function PublicTenantList() {
  const tenants = useListPublicTenants();

  return (
    <>
      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-semibold">公開されているテナント</h1>
        <p className="text-sm text-muted-foreground">参加すると、そのテナントのクイズを解けます</p>
      </div>
      {tenants.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : tenants.isError ? (
        <ApiErrorAlert error={tenants.error} />
      ) : tenants.data.length === 0 ? (
        <p className="text-center text-sm text-muted-foreground">公開されているテナントはありません。</p>
      ) : (
        <ul className="space-y-3">
          {tenants.data.map((tenant) => (
            <li key={tenant.slug}>
              <PublicTenantCard tenant={tenant} />
            </li>
          ))}
        </ul>
      )}
      <Link href="/" className="text-center text-sm text-muted-foreground underline underline-offset-4">
        所属しているテナントへ戻る
      </Link>
    </>
  );
}

function PublicTenantCard({ tenant }: { tenant: PublicTenantResponse }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const join = useJoinPublicTenant({
    mutation: {
      onSuccess: async (joined) => {
        await queryClient.invalidateQueries({ queryKey: getListMyTenantsQueryKey() });
        router.push(`/t/${joined.slug}`);
      },
    },
  });

  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <CardTitle className="break-words">{tenant.name}</CardTitle>
          <CardDescription>/t/{tenant.slug}</CardDescription>
        </div>
        {tenant.joined ? (
          <div className="flex items-center gap-2">
            <Badge variant="secondary">参加済み</Badge>
            <Button asChild size="sm" variant="outline">
              <Link href={`/t/${tenant.slug}`}>開く</Link>
            </Button>
          </div>
        ) : (
          <Button
            size="sm"
            disabled={join.isPending}
            aria-label={`${tenant.name} に参加する`}
            onClick={() => join.mutate({ slug: tenant.slug })}
          >
            {join.isPending ? "参加しています…" : "参加する"}
          </Button>
        )}
      </CardHeader>
      {join.isError && (
        <div className="px-6 pb-6">
          <ApiErrorAlert error={join.error} />
        </div>
      )}
    </Card>
  );
}
