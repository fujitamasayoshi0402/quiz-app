"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useListMyTenants } from "@/lib/api/generated/endpoints";

/**
 * 管理画面の入口。このテナントの管理者でなければ、中身を描かずに案内を 1 つ出す。
 *
 * **表示の都合であって、権限の判定ではない。** 判定はバックエンドがパスで行い、この画面を通さずに API を呼んでも 403 になる。
 * ここで止めないと、一般ユーザーが URL を直接開いたとき、画面の中の API がそれぞれ 403 を返してエラーが並ぶ。
 *
 * 所属していないテナントと、存在しないテナントは同じ表示にする。バックエンドが 404 で揃えているのと同じ理由で、
 * テナントがあるかどうかを見せない。所属の一覧に無い、という事実しか使わないので、区別のしようもない。
 *
 * 所属の一覧はブラウザから取る。ヘッダのナビと同じクエリなので、キャッシュが効いて要求は増えない。
 */
export function AdminGuard({ slug, children }: { slug: string; children: ReactNode }) {
  const tenants = useListMyTenants();

  if (tenants.isPending) return <Skeleton className="h-64 w-full" />;
  if (tenants.isError) return <ApiErrorAlert error={tenants.error} />;

  const current = tenants.data.find((tenant) => tenant.slug === slug);
  if (!current) {
    return (
      <Notice
        title="テナントが見つかりません"
        description="所属しているテナントから選んでください。"
        href="/"
        label="テナントの一覧へ"
      />
    );
  }
  if (current.role !== "admin") {
    return (
      <Notice
        title="管理者だけが使える画面です"
        description={`${current.name} には一般ユーザーとして所属しています。`}
        href={`/t/${slug}/play`}
        label="問題を解く"
      />
    );
  }
  return children;
}

function Notice({
  title,
  description,
  href,
  label,
}: {
  title: string;
  description: string;
  href: string;
  label: string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild variant="outline">
          <Link href={href}>{label}</Link>
        </Button>
      </CardContent>
    </Card>
  );
}
