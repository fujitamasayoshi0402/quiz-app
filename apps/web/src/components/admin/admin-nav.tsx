"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const ITEMS = [
  { segment: "quizzes", label: "クイズ" },
  { segment: "categories", label: "カテゴリ" },
  { segment: "trash", label: "削除済み" },
  { segment: "invitations", label: "招待" },
  { segment: "notifications", label: "通知" },
];

export function AdminNav({ slug }: { slug: string }) {
  const pathname = usePathname();
  return (
    // 狭い幅では余白を詰めて 1 行に収める。収まらないときも、項目の途中では折り返さない
    <nav className="flex flex-wrap border-b sm:gap-1">
      {ITEMS.map((item) => {
        const href = `/t/${slug}/admin/${item.segment}`;
        const active = pathname.startsWith(href);
        return (
          <Link
            key={item.segment}
            href={href}
            className={cn(
              "-mb-px border-b-2 px-2 py-2 text-sm whitespace-nowrap sm:px-3",
              active ? "border-primary font-medium" : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
