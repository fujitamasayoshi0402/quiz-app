import { PlaySetup } from "@/components/play/play-setup";

export default async function PlayPage({ params }: PageProps<"/t/[slug]/play">) {
  const { slug } = await params;
  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold tracking-tight">クイズを選ぶ</h1>
      <PlaySetup slug={slug} />
    </div>
  );
}
