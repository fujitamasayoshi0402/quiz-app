import { DemoWarmUp } from "@/components/auth/demo-warm-up";
import { Button } from "@/components/ui/button";

/**
 * 共有のデモのアカウントで入る（ADR-0029）。ルートハンドラ（`/auth/demo`）へ POST する。
 * セッションの Cookie を作って、トップへ移す。トップは所属が 1 つなので、デモのテナントへ移る。
 *
 * 表示した時点で、止まっている DB を起こし始める（DEV-195）
 */
export function DemoSignInForm() {
  return (
    <form action="/auth/demo" method="post">
      <Button type="submit" className="w-full">
        デモとして入る
      </Button>
      <DemoWarmUp />
    </form>
  );
}
