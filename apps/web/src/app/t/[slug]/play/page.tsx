import { PlaySetup } from "@/components/play/play-setup";

export default async function PlayPage({ params }: PageProps<"/t/[slug]/play">) {
  const { slug } = await params;
  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">クイズを選ぶ</h1>
      <PlaySetup slug={slug} />
    </div>
  );
}
