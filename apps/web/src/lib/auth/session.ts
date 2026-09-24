import { cookies } from "next/headers";
import { USER_COOKIE, findStubUser } from "@/lib/auth/stub-users";

/** ログインしている利用者。Cookie が無いか、選べない利用者なら undefined */
export async function currentUser() {
  return findStubUser((await cookies()).get(USER_COOKIE)?.value);
}
