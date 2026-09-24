"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { USER_COOKIE, findStubUser } from "@/lib/auth/stub-users";

/** スタブのログイン。選んだ利用者を Cookie に置き、テナントの選択へ進む。 */
export async function signInAs(formData: FormData) {
  const user = findStubUser(formData.get("userId")?.toString());
  if (!user) {
    throw new Error("選べない利用者です");
  }
  const cookieStore = await cookies();
  cookieStore.set(USER_COOKIE, user.id, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect("/");
}

/** ログアウト。利用者を選び直す画面へ戻る。 */
export async function signOut() {
  const cookieStore = await cookies();
  cookieStore.delete(USER_COOKIE);
  redirect("/login");
}
