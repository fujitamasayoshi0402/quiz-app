"use client";

import { Eye } from "lucide-react";
import Link from "next/link";
import { createContext, type ReactNode, useContext } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useListMyTenants } from "@/lib/api/generated/endpoints";
import { canOpenAdmin } from "@/lib/auth/roles";

const ViewerContext = createContext(false);

/** 見学者（ADR-0035）として管理画面を見ているか。見学者に見せない画面が、API を呼ばずに案内を出すのに使う */
export function useViewing() {
  return useContext(ViewerContext);
}

/**
 * 管理画面の入口。このテナントの管理者でなければ、中身を描かずに案内を 1 つ出す。
 * 見学者（ADR-0035）には中身を描き、見学中であることを上に出す。変えようとすると、バックエンドが 403 を返し、その理由が画面に出る。
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
  if (!canOpenAdmin(current.role)) {
    return (
      <Notice
        title="管理者だけが使える画面です"
        description={`${current.name} には一般ユーザーとして所属しています。`}
        href={`/t/${slug}/play`}
        label="問題を解く"
      />
    );
  }
  if (current.role === "viewer") {
    return (
      <ViewerContext value={true}>
        <div className="space-y-6">
          <ViewerNotice />
          {children}
        </div>
      </ViewerContext>
    );
  }
  return children;
}

/** 見学中であることと、管理を試す方法 */
function ViewerNotice() {
  return (
    <Alert>
      <Eye />
      <AlertTitle>見学中です</AlertTitle>
      <AlertDescription>
        <p>
          デモのテナントの管理画面を、見るだけで使えます。保存や削除を押しても、変更はされません。
          自分のアカウントを作ると、自分のテナントでクイズや図を作れます。
        </p>
        <Link href={`/login?returnTo=${encodeURIComponent("/tenants/new")}`} className="underline underline-offset-4">
          アカウントを作って試す
        </Link>
      </AlertDescription>
    </Alert>
  );
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
