import { ApiError, readProblem } from "@/lib/api/fetcher";
import { accessToken, forgetSession } from "@/lib/auth/client";
import type { Environment } from "@/lib/environments";

/**
 * 結果の共有のリンクを作る（DEV-212）。web と同じく、web のサーバー（`apps/web/src/app/share/route.ts`）が作る。
 * 点数は web のサーバーが完了 API から取るので、送るのは挑戦の ID だけ。
 *
 * アプリは web のセッションを持たないため、API を呼ぶときと同じトークンを `Authorization` に付ける
 */
export async function createShareLink(environment: Environment, slug: string, attemptId: string): Promise<string> {
  if (!environment.webOrigin) throw new Error("web の origin が設定されていません");
  const token = await accessToken(environment);
  const response = await fetch(`${environment.webOrigin}/share`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ slug, attemptId }),
  });
  if (!response.ok) {
    if (response.status === 401 && token) await forgetSession(environment);
    throw new ApiError(response.status, await readProblem(response));
  }
  const { url } = (await response.json()) as { url: string };
  return url;
}
