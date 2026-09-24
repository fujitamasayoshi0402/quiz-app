"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { DEFAULT_TENANT, USER_COOKIE, findStubUser } from "@/lib/auth/stub-users";

/** スタブのログイン。選んだ利用者を Cookie に置き、テナントへ進む。 */
export async function signInAs(formData: FormData) {
  const user = findStubUser(formData.get("userId")?.toString());
  if (!user) {
    throw new Error("選べない利用者です");
  }
  const cookieStore = await cookies();
  cookieStore.set(USER_COOKIE, user.id, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(`/t/${DEFAULT_TENANT}/play`);
}
