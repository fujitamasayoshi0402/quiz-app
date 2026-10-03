import { TenantSettings } from "@/components/admin/tenant-settings";

export default async function SettingsPage({ params }: PageProps<"/t/[slug]/admin/settings">) {
  const { slug } = await params;
  return <TenantSettings slug={slug} />;
}
