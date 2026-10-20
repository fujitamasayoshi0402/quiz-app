import { Button } from "@/components/ui/button";

/**
 * 共有のデモのアカウントで入る（ADR-0029）。ルートハンドラ（`/auth/demo`）へ POST する。
 * セッションの Cookie を作って、トップへ移す。トップは所属が 1 つなので、デモのテナントへ移る
 */
export function DemoSignInForm() {
  return (
    <form action="/auth/demo" method="post">
      <Button type="submit" className="w-full">
        デモとして入る
      </Button>
    </form>
  );
}
