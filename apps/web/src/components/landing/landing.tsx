import Image, { type StaticImageData } from "next/image";
import Link from "next/link";
import { DemoSignInForm } from "@/components/auth/demo-sign-in-form";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import adminQuizzes from "@/assets/screenshots/admin-quizzes.png";
import history from "@/assets/screenshots/history.png";
import playAnswer from "@/assets/screenshots/play-answer.png";

/** 画面の例。README と同じ画像で、撮影のスクリプトが `src/assets/screenshots/` にも写す（`tests/e2e/capture/support.ts`） */
const screenshots: { image: StaticImageData; title: string; alt: string }[] = [
  { image: playAnswer, title: "正誤と解説", alt: "選んだ答えの正誤と、Markdown で書いた解説" },
  { image: history, title: "履歴と正答率", alt: "カテゴリごとの正答率と、解いたクイズの一覧" },
  { image: adminQuizzes, title: "管理画面", alt: "管理者が作ったクイズの一覧" },
];

const features = [
  "カテゴリと難易度を選んで、4 択のクイズを解く。正誤と解説（図・画像・PDF を含む）を、その場で読める",
  "回答の履歴とカテゴリごとの正答率を見て、間違えた問題を解き直す。忘れたころに「今日の復習」に出る",
  "管理者は、カテゴリ・難易度・クイズを作り、解説に draw.io の図を描いて入れる。テナントごとに独立した空間を持つ",
  "公式の技術ニュースから、AI がクイズの下書きを毎日作る。管理者が確かめてから公開する",
];

/**
 * ログインしていない人のトップ（DEV-193）。何のアプリかを先に伝え、2 つの入口を出す。
 *
 * - デモとして入る: 共有のデモのアカウントで、すぐに解ける（ADR-0029）。デモのアカウントが設定されていない環境では出さない
 * - 管理を試す: アカウントを作り、自分のテナントを作る（ADR-0028）。ログインの画面を経て、テナントの作成へ戻る
 */
export function Landing({ demo }: { demo: boolean }) {
  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-10 px-4 py-10 sm:py-16">
      <section className="space-y-3 text-center">
        <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Quiz</h1>
        <p className="text-base text-pretty sm:text-lg">
          カテゴリと難易度を自由に決めて、4 択のクイズを作って解けるアプリです。
        </p>
        <p className="text-sm text-pretty text-muted-foreground">
          解説には図・画像・PDF を入れられます。管理者ごとに独立したクイズの空間（テナント）を持ちます。
        </p>
      </section>

      <section className={demo ? "grid gap-4 sm:grid-cols-2" : "mx-auto grid w-full max-w-md gap-4"}>
        {demo && (
          <Card>
            <CardHeader>
              <CardTitle>デモとして入る</CardTitle>
              <CardDescription>
                アカウントを作らずに、デモのクイズ（世界の地理、身近な科学など）を解けます。管理画面も、見るだけなら開けます。共有のアカウントのため、回答の履歴とランキングは、見た人どうしで共有されます。
              </CardDescription>
            </CardHeader>
            <CardContent className="mt-auto">
              <DemoSignInForm />
            </CardContent>
          </Card>
        )}
        <Card>
          <CardHeader>
            <CardTitle>管理を試す</CardTitle>
            <CardDescription>
              自分のアカウントを作り（メールアドレスの確認があります）、自分のテナントでカテゴリ・クイズ・解説の図を作れます。
              ログインの画面（Amazon Cognito）へ移ります。
            </CardDescription>
          </CardHeader>
          <CardContent className="mt-auto">
            <Button asChild variant={demo ? "outline" : "default"} className="w-full">
              <Link href={`/login?returnTo=${encodeURIComponent("/tenants/new")}`}>アカウントを作って始める</Link>
            </Button>
          </CardContent>
        </Card>
      </section>

      <p className="-mt-4 text-center text-sm text-muted-foreground">
        アカウントをお持ちの方は{" "}
        <Link href="/login" className="text-foreground underline underline-offset-4">
          ログイン
        </Link>
      </p>

      <section className="space-y-4">
        <h2 className="text-center text-lg font-semibold">画面の例</h2>
        <ul className="grid gap-4 sm:grid-cols-3">
          {screenshots.map(({ image, title, alt }) => (
            <li key={title}>
              <figure className="space-y-2">
                <Image
                  src={image}
                  alt={alt}
                  sizes="(min-width: 640px) 300px, 100vw"
                  className="w-full rounded-lg border bg-background"
                />
                <figcaption className="text-center text-sm text-muted-foreground">{title}</figcaption>
              </figure>
            </li>
          ))}
        </ul>
      </section>

      <section className="mx-auto w-full max-w-2xl space-y-4">
        <h2 className="text-center text-lg font-semibold">できること</h2>
        <ul className="list-disc space-y-2 pl-5 text-sm">
          {features.map((feature) => (
            <li key={feature}>{feature}</li>
          ))}
        </ul>
      </section>

      <footer className="text-center text-sm text-muted-foreground">
        <a
          href="https://github.com/fujitamasayoshi0402/quiz-app"
          target="_blank"
          rel="noreferrer"
          className="underline underline-offset-4"
        >
          ソースコードと設計の記録（GitHub）
        </a>
      </footer>
    </main>
  );
}
