import { ApiError } from "@/lib/api/fetcher";
import { completeFigureUpload, startFigureUpload } from "@/lib/api/generated/endpoints";

/** 上げられるファイルと、大きさの上限。最終的な判定は、中身を読むバックエンド（ADR-0020） */
const LIMITS: Record<string, { maxBytes: number; tooLarge: string }> = {
  "image/png": { maxBytes: 10 * 1024 * 1024, tooLarge: "画像は 10 MB までです" },
  "image/jpeg": { maxBytes: 10 * 1024 * 1024, tooLarge: "画像は 10 MB までです" },
  "application/pdf": { maxBytes: 20 * 1024 * 1024, tooLarge: "PDF は 20 MB までです" },
};

export const FIGURE_FILE_TYPES = Object.keys(LIMITS);

/** 上げる前に、画面で分かる誤りを返す。無ければ undefined */
export function figureFileProblem(file: { type: string; size: number }): string | undefined {
  const limit = LIMITS[file.type];
  if (!limit) return "PNG・JPEG の画像か、PDF を選んでください";
  if (file.size === 0) return "空のファイルは上げられません";
  if (file.size > limit.maxBytes) return limit.tooLarge;
  return undefined;
}

/**
 * 画像か PDF を解説図として置き、図の ID と種類を返す（ADR-0020）。
 *
 * 1. API が図の ID と、上げる URL を出す
 * 2. ブラウザが本体を S3 へ直接 PUT する。種類と大きさは署名に含まれていて、違えば S3 が拒む
 * 3. API に完了を伝える。API が中身で種類を決め、画像は読み直し、PDF はそのまま置く
 *
 * 種類は、申告ではなく API が中身で決めたものを返す。本文への入れ方（画像か、リンクか）はこれで決める。
 * 大きな本体は、web の proxy も quiz-service も通らない。
 */
export async function uploadFigureFile(slug: string, file: File): Promise<{ id: string; kind: string }> {
  const problem = figureFileProblem(file);
  if (problem) throw uploadError(problem);

  const upload = await startFigureUpload(slug, { contentType: file.type, size: file.size });
  const uploaded = await fetch(upload.url, { method: "PUT", headers: upload.headers, body: file }).catch(() => null);
  if (!uploaded?.ok) throw uploadError("ファイルを上げられませんでした。時間をおいてもう一度お試しください");

  return completeFigureUpload(slug, upload.id);
}

/** 画面の誤りも、API の誤りと同じ形で出す（ApiErrorAlert） */
function uploadError(detail: string): ApiError {
  return new ApiError(400, { title: "ファイルを入れられません", detail });
}
