import { redirect } from "next/navigation";
import { InvitationAcceptance } from "@/components/invitation/invitation-acceptance";
import { currentUser } from "@/lib/auth/current-user";

/**
 * 招待のリンクの行き先。テナントの外に置く。受け入れる人は、まだどのテナントにも所属していないことがある。
 *
 * ログインしていなければ、ログインのあとにここへ戻す。はじめての人は、ログインの画面でアカウントを作る。
 */
export default async function InvitationPage({ params }: PageProps<"/invitations/[token]">) {
  const { token } = await params;
  const user = await currentUser();
  if (!user) {
    redirect(`/login?returnTo=${encodeURIComponent(`/invitations/${token}`)}`);
  }

  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <InvitationAcceptance token={token} email={user.email} />
    </main>
  );
}
