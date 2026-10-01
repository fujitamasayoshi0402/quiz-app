import { SlackWebhookSettings } from "@/components/admin/slack-webhook-settings";

export default async function NotificationsPage({ params }: PageProps<"/t/[slug]/admin/notifications">) {
  const { slug } = await params;
  return <SlackWebhookSettings slug={slug} />;
}
