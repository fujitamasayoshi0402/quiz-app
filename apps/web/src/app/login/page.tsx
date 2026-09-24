import { signInAs } from "@/app/actions";
import { STUB_USERS } from "@/lib/auth/stub-users";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * スタブのログイン画面。
 *
 * Phase 3 でパスキーのログインに置き換える。それまではシードの利用者から選ぶ。
 */
export default function Login() {
  return (
    <main className="mx-auto flex min-h-svh max-w-md flex-col justify-center gap-6 px-4 py-8">
      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-semibold">Quiz</h1>
        <p className="text-muted-foreground text-sm">利用者を選んで始めます（開発用のスタブ認証）</p>
      </div>
      {STUB_USERS.map((user) => (
        <Card key={user.id}>
          <CardHeader>
            <CardTitle>{user.name}</CardTitle>
            <CardDescription>{user.description}</CardDescription>
          </CardHeader>
          <CardContent>
            <form action={signInAs}>
              <input type="hidden" name="userId" value={user.id} />
              <Button type="submit" className="w-full">
                {user.name}として始める
              </Button>
            </form>
          </CardContent>
        </Card>
      ))}
    </main>
  );
}
