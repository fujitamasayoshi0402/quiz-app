"use client";

import Link from "next/link";
import { useState } from "react";
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
import { LeaveTenantDialog } from "./leave-tenant-dialog";

/**
 * ヘッダのテナント名とナビゲーション。テナント名のメニューから、ほかのテナントへ移る。
 * 所属が 2 つ以上なら切り替え、いつでも公開テナントの一覧とテナントの作成（ADR-0028）。
 * 所属が 1 つの人は `/` からそのテナントへ送られるため、ここに置かないと一覧や作成への道がない。
 * 同じ理由で、テナントからの退会（ADR-0030）もこのメニューに置く。
 *
 * 「管理」は管理者として所属しているテナントでだけ出す。**表示の都合であって、権限の判定ではない。**
 * 判定はバックエンドが行い、一般ユーザーが管理画面の URL を直接開いても API が 403 を返す。
 * 画面の入口でも、管理者でなければ案内を出して止める（`AdminGuard`）。
 */
export function TenantNav({ slug }: { slug: string }) {
  const tenants = useListMyTenants();
  const current = tenants.data?.find((tenant) => tenant.slug === slug);
  const name = current?.name ?? slug;
  const [leaving, setLeaving] = useState(false);

  return (
    <div className="flex min-w-0 items-center gap-4">
      <DropdownMenu>
        <DropdownMenuTrigger className="-mx-2 flex min-w-0 items-center gap-1 rounded-md px-2 py-1 font-semibold hover:bg-accent">
          <span className="truncate">{name}</span>
          <ChevronsUpDown className="size-4 shrink-0 text-muted-foreground" />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-56">
          {tenants.data && tenants.data.length > 1 && (
            <>
              <DropdownMenuLabel>テナントを切り替える</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {tenants.data.map((tenant) => (
                <DropdownMenuItem key={tenant.slug} asChild>
                  <Link href={`/t/${tenant.slug}`}>
                    <span className="flex-1">{tenant.name}</span>
                    <span className="text-xs text-muted-foreground">{roleLabel(tenant.role)}</span>
                    <Check className={tenant.slug === slug ? "size-4" : "invisible size-4"} />
                  </Link>
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
            </>
          )}
          <DropdownMenuItem asChild>
            <Link href="/tenants">公開されているテナントを探す</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link href="/tenants/new">自分のテナントを作る</Link>
          </DropdownMenuItem>
          {current && (
            <>
              <DropdownMenuSeparator />
              {/* メニューが閉じてからダイアログを開く。メニューの中に置くと、メニューと一緒に閉じる */}
              <DropdownMenuItem variant="destructive" onSelect={() => setLeaving(true)}>
                このテナントから退会する
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <LeaveTenantDialog slug={slug} name={name} open={leaving} onOpenChange={setLeaving} />
      {/* py-2 はタップできる範囲を広げるため。ヘッダの高さに収まるので見た目は変わらない */}
      <nav className="flex shrink-0 gap-3 text-sm">
        <Link href={`/t/${slug}/play`} className="py-2 text-muted-foreground hover:text-foreground">
          解く
        </Link>
        <Link href={`/t/${slug}/history`} className="py-2 text-muted-foreground hover:text-foreground">
          履歴
        </Link>
        {/* 320px の幅でテナント名が押し出されないよう、狭い幅では短く出す */}
        <Link href={`/t/${slug}/ranking`} className="py-2 text-muted-foreground hover:text-foreground">
          <span className="sm:hidden">順位</span>
          <span className="hidden sm:inline">ランキング</span>
        </Link>
        {current?.role === "admin" && (
          <Link href={`/t/${slug}/admin`} className="py-2 text-muted-foreground hover:text-foreground">
            管理
          </Link>
        )}
      </nav>
    </div>
  );
}
