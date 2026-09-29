import { ApiError } from "@/lib/api/fetcher";
import { completeFigureUpload, startFigureUpload } from "@/lib/api/generated/endpoints";

/** 上げられる画像。最終的な判定は、中身を読むバックエンド（ADR-0020） */
export const IMAGE_TYPES = ["image/png", "image/jpeg"];
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

/** 上げる前に、画面で分かる誤りを返す。無ければ undefined */
export function imageUploadProblem(file: { type: string; size: number }): string | undefined {
  if (!IMAGE_TYPES.includes(file.type)) return "PNG か JPEG の画像を選んでください";
  if (file.size === 0) return "空のファイルは上げられません";
  if (file.size > MAX_IMAGE_BYTES) return "画像は 10 MB までです";
  return undefined;
}

/**
 * 画像を解説図として置き、図の ID を返す（ADR-0020）。
 *
 * 1. API が図の ID と、上げる URL を出す
 * 2. ブラウザが本体を S3 へ直接 PUT する。種類と大きさは署名に含まれていて、違えば S3 が拒む
 * 3. API に完了を伝える。API が中身を検査し、読み直したものを置く
 *
 * 大きな本体は、web の proxy も quiz-service も通らない。
 */
export async function uploadImage(slug: string, file: File): Promise<string> {
  const problem = imageUploadProblem(file);
  if (problem) throw uploadError(problem);

  const upload = await startFigureUpload(slug, { contentType: file.type, size: file.size });
  const uploaded = await fetch(upload.url, { method: "PUT", headers: upload.headers, body: file }).catch(() => null);
  if (!uploaded?.ok) throw uploadError("画像を上げられませんでした。時間をおいてもう一度お試しください");

  const { id } = await completeFigureUpload(slug, upload.id);
  return id;
}

/** 画面の誤りも、API の誤りと同じ形で出す（ApiErrorAlert） */
function uploadError(detail: string): ApiError {
  return new ApiError(400, { title: "画像を入れられません", detail });
}
