import { Button } from "@/components/ui/button";

/** ログアウト。ルートハンドラ（`/auth/logout`）へ POST する。GET にするとほかのサイトからログアウトさせられる */
export function SignOutButton({ className }: { className?: string }) {
  return (
    <form action="/auth/logout" method="post" className={className}>
      <Button type="submit" variant="link" size="sm" className="h-auto px-0 py-2">
        ログアウト
      </Button>
    </form>
  );
}
