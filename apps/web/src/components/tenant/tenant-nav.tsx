"use client";

import Link from "next/link";
import { Check, ChevronsUpDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useListMyTenants } from "@/lib/api/generated/endpoints";
import { roleLabel } from "@/lib/auth/roles";

/**
 * ヘッダのテナント名とナビゲーション。所属が 2 つ以上なら、テナント名から切り替えられる。
 *
 * 「管理」は管理者として所属しているテナントでだけ出す。**表示の都合であって、権限の判定ではない。**
 * 判定はバックエンドが行い、一般ユーザーが管理画面の URL を直接開いても API が 403 を返す。
 * 画面の入口でも、管理者でなければ案内を出して止める（`AdminGuard`）。
 */
export function TenantNav({ slug }: { slug: string }) {
  const tenants = useListMyTenants();
  const current = tenants.data?.find((tenant) => tenant.slug === slug);
  const name = current?.name ?? slug;

  return (
    <div className="flex min-w-0 items-center gap-4">
      {tenants.data && tenants.data.length > 1 ? (
        <DropdownMenu>
          <DropdownMenuTrigger className="hover:bg-accent -mx-2 flex min-w-0 items-center gap-1 rounded-md px-2 py-1 font-semibold">
            <span className="truncate">{name}</span>
            <ChevronsUpDown className="text-muted-foreground size-4 shrink-0" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="min-w-56">
            <DropdownMenuLabel>テナントを切り替える</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {tenants.data.map((tenant) => (
              <DropdownMenuItem key={tenant.slug} asChild>
                <Link href={`/t/${tenant.slug}`}>
                  <span className="flex-1">{tenant.name}</span>
                  <span className="text-muted-foreground text-xs">{roleLabel(tenant.role)}</span>
                  <Check className={tenant.slug === slug ? "size-4" : "invisible size-4"} />
                </Link>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <Link href={`/t/${slug}/play`} className="truncate font-semibold">
          {name}
        </Link>
      )}
      {/* py-2 はタップできる範囲を広げるため。ヘッダの高さに収まるので見た目は変わらない */}
      <nav className="flex shrink-0 gap-3 text-sm">
        <Link href={`/t/${slug}/play`} className="text-muted-foreground hover:text-foreground py-2">
          解く
        </Link>
        <Link href={`/t/${slug}/history`} className="text-muted-foreground hover:text-foreground py-2">
          履歴
        </Link>
        {current?.role === "admin" && (
          <Link href={`/t/${slug}/admin`} className="text-muted-foreground hover:text-foreground py-2">
            管理
          </Link>
        )}
      </nav>
    </div>
  );
}
