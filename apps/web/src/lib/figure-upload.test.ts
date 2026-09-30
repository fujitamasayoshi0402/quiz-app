import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/fetcher";
import { completeFigureUpload, startFigureUpload } from "@/lib/api/generated/endpoints";
import { figureFileProblem, uploadFigureFile } from "./figure-upload";

vi.mock("@/lib/api/generated/endpoints", () => ({
  startFigureUpload: vi.fn(),
  completeFigureUpload: vi.fn(),
}));

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const UPLOAD_URL = "https://bucket.example/incoming/t/id?X-Amz-Signature=x";

function png(size = 100): File {
  return new File([new Uint8Array(size)], "図.png", { type: "image/png" });
}

describe("画像と PDF を上げる", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(startFigureUpload).mockResolvedValue({
      id: ID,
      url: UPLOAD_URL,
      headers: { "Content-Type": "image/png" },
    });
    vi.mocked(completeFigureUpload).mockResolvedValue({ id: ID, kind: "image" });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("上げる前に、画面で分かる誤りを伝える。大きさの上限は画像と PDF で違う", () => {
    const MB = 1024 * 1024;
    expect(figureFileProblem({ type: "image/png", size: 1 })).toBeUndefined();
    expect(figureFileProblem({ type: "image/jpeg", size: 10 * MB })).toBeUndefined();
    expect(figureFileProblem({ type: "application/pdf", size: 20 * MB })).toBeUndefined();
    expect(figureFileProblem({ type: "image/gif", size: 1 })).toBe("PNG・JPEG の画像か、PDF を選んでください");
    expect(figureFileProblem({ type: "image/png", size: 0 })).toBe("空のファイルは上げられません");
    expect(figureFileProblem({ type: "image/png", size: 10 * MB + 1 })).toBe("画像は 10 MB までです");
    expect(figureFileProblem({ type: "application/pdf", size: 20 * MB + 1 })).toBe("PDF は 20 MB までです");
  });

  it("準備で受け取った URL へ、受け取ったヘッダのまま本体を PUT し、完了を伝える。種類は API が決めたもの", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));
    const file = png(1234);

    await expect(uploadFigureFile("demo", file)).resolves.toEqual({ id: ID, kind: "image" });

    expect(startFigureUpload).toHaveBeenCalledWith("demo", { contentType: "image/png", size: 1234 });
    expect(fetchMock).toHaveBeenCalledWith(UPLOAD_URL, {
      method: "PUT",
      headers: { "Content-Type": "image/png" },
      body: file,
    });
    expect(completeFigureUpload).toHaveBeenCalledWith("demo", ID);
  });

  it("画像でも PDF でもないファイルは、API を呼ばずに断る", async () => {
    const error = await uploadFigureFile("demo", new File(["<html>"], "a.html", { type: "text/html" })).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.problem.detail).toBe("PNG・JPEG の画像か、PDF を選んでください");
    expect(startFigureUpload).not.toHaveBeenCalled();
  });

  it.each([
    ["S3 が拒んだ", () => fetchMock.mockResolvedValue(new Response(null, { status: 403 }))],
    ["通信に失敗した", () => fetchMock.mockRejectedValue(new TypeError("Failed to fetch"))],
  ])("%sときは、完了を伝えずに理由を返す", async (_, arrange) => {
    arrange();

    const error = await uploadFigureFile("demo", png()).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.problem.detail).toBe("ファイルを上げられませんでした。時間をおいてもう一度お試しください");
    expect(completeFigureUpload).not.toHaveBeenCalled();
  });
});
