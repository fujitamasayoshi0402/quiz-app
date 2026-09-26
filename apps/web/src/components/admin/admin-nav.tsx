"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const ITEMS = [
  { segment: "quizzes", label: "クイズ" },
  { segment: "categories", label: "カテゴリ" },
  { segment: "trash", label: "削除済み" },
  { segment: "invitations", label: "招待" },
];

export function AdminNav({ slug }: { slug: string }) {
  const pathname = usePathname();
  return (
    <nav className="flex gap-1 border-b">
      {ITEMS.map((item) => {
        const href = `/t/${slug}/admin/${item.segment}`;
        const active = pathname.startsWith(href);
        return (
          <Link
            key={item.segment}
            href={href}
            className={cn(
              "-mb-px border-b-2 px-3 py-2 text-sm",
              active ? "border-primary font-medium" : "text-muted-foreground hover:text-foreground border-transparent",
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
