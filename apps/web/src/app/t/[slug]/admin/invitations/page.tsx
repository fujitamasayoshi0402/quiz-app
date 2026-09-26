import { InvitationManager } from "@/components/admin/invitation-manager";

export default async function InvitationsPage({ params }: PageProps<"/t/[slug]/admin/invitations">) {
  const { slug } = await params;
  return <InvitationManager slug={slug} />;
}
