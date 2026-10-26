import { AlertCircle } from "lucide-react";
import { DemoSignInForm } from "@/components/auth/demo-sign-in-form";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { demoCredentials } from "@/lib/auth/demo";
import { safeReturnTo } from "@/lib/auth/request";

/**
 * ログインの入口。サインアップとログインは、認証基盤の画面（Cognito の Managed Login）で行う（ADR-0016）。
 *
 * この画面を挟むのは、認証基盤の画面へ移ることを先に伝えるため。いきなり別のドメインへ移ると、偽の画面と見分けにくい。
 *
 * 見に来た人が試せるよう、共有のデモのアカウントで入る「デモとして入る」も置く（ADR-0029）。
 * 招待のリンクから来た人には出さない。招待されたアドレスでログインしてもらう
 */
export default async function Login({ searchParams }: PageProps<"/login">) {
  const params = await searchParams;
  const returnTo = safeReturnTo(typeof params.returnTo === "string" ? params.returnTo : undefined);
  const failed = params.error !== undefined;
  const demoFailed = params.error === "demo";
  // 招待のリンクから来た。別のアドレスでログインすると、招待を受け入れられない
  const invited = returnTo.startsWith("/invitations/");
  const demo = !invited && demoCredentials() !== undefined;

  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-semibold">Quiz</h1>
        <p className="text-sm text-muted-foreground">パスキーまたはパスワードでログインします</p>
      </div>
      {failed && (
        <Alert variant="destructive">
          <AlertCircle />
          <AlertTitle>{demoFailed ? "デモに入れませんでした" : "ログインできませんでした"}</AlertTitle>
          <AlertDescription>
            {demoFailed ? "しばらくしてから、もう一度お試しください" : "もう一度お試しください"}
          </AlertDescription>
        </Alert>
      )}
      {demo && (
        <Card>
          <CardHeader>
            <CardTitle>デモを試す</CardTitle>
            <CardDescription>
              アカウントを作らずに、デモのテナントのクイズを解けます。管理画面も、見るだけなら開けます。共有のアカウントのため、回答の履歴とランキングは、見た人どうしで共有されます。
            </CardDescription>
          </CardHeader>
          <CardContent>
            <DemoSignInForm />
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader>
          <CardTitle>ログイン</CardTitle>
          <CardDescription>
            ログインの画面（Amazon Cognito）へ移ります。はじめての方は、そこでアカウントを作れます。
            作ったあとにパスキーを登録すると、次からはパスワードなしで入れます。
            {invited && (
              <strong className="mt-2 block text-foreground">
                招待されたメールアドレスで、ログインまたはアカウントの作成をしてください。
              </strong>
            )}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild className="w-full" variant={demo ? "outline" : "default"}>
            {/* ルートハンドラへの遷移なので、クライアント側の遷移（next/link）は使わない */}
            <a href={`/auth/login?returnTo=${encodeURIComponent(returnTo)}`}>ログイン / アカウントを作る</a>
          </Button>
        </CardContent>
      </Card>
    </main>
  );
}
