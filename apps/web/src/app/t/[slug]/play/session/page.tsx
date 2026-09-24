import { redirect } from "next/navigation";
import { PlaySession } from "@/components/play/play-session";
import { parseMode } from "@/lib/play/mode";

export default async function SessionPage({ params, searchParams }: PageProps<"/t/[slug]/play/session">) {
  const { slug } = await params;
  const { attempt, mode } = await searchParams;
  if (typeof attempt !== "string") redirect(`/t/${slug}/play`);
  return <PlaySession slug={slug} attemptId={attempt} mode={parseMode(mode)} />;
}
