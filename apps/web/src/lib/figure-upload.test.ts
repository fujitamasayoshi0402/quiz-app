import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/fetcher";
import { completeFigureUpload, startFigureUpload } from "@/lib/api/generated/endpoints";
import { MAX_IMAGE_BYTES, imageUploadProblem, uploadImage } from "./figure-upload";

vi.mock("@/lib/api/generated/endpoints", () => ({
  startFigureUpload: vi.fn(),
  completeFigureUpload: vi.fn(),
}));

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const UPLOAD_URL = "https://bucket.example/incoming/t/id?X-Amz-Signature=x";

function png(size = 100): File {
  return new File([new Uint8Array(size)], "図.png", { type: "image/png" });
}

describe("画像を上げる", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(startFigureUpload).mockResolvedValue({ id: ID, url: UPLOAD_URL, headers: { "Content-Type": "image/png" } });
    vi.mocked(completeFigureUpload).mockResolvedValue({ id: ID });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("上げる前に、画面で分かる誤りを伝える", () => {
    expect(imageUploadProblem({ type: "image/png", size: 1 })).toBeUndefined();
    expect(imageUploadProblem({ type: "image/jpeg", size: MAX_IMAGE_BYTES })).toBeUndefined();
    expect(imageUploadProblem({ type: "image/gif", size: 1 })).toBe("PNG か JPEG の画像を選んでください");
    expect(imageUploadProblem({ type: "image/png", size: 0 })).toBe("空のファイルは上げられません");
    expect(imageUploadProblem({ type: "image/png", size: MAX_IMAGE_BYTES + 1 })).toBe("画像は 10 MB までです");
  });

  it("準備で受け取った URL へ、受け取ったヘッダのまま本体を PUT し、完了を伝える", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));
    const file = png(1234);

    await expect(uploadImage("demo", file)).resolves.toBe(ID);

    expect(startFigureUpload).toHaveBeenCalledWith("demo", { contentType: "image/png", size: 1234 });
    expect(fetchMock).toHaveBeenCalledWith(UPLOAD_URL, {
      method: "PUT",
      headers: { "Content-Type": "image/png" },
      body: file,
    });
    expect(completeFigureUpload).toHaveBeenCalledWith("demo", ID);
  });

  it("画像でないファイルは、API を呼ばずに断る", async () => {
    const error = await uploadImage("demo", new File(["<html>"], "a.html", { type: "text/html" })).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.problem.detail).toBe("PNG か JPEG の画像を選んでください");
    expect(startFigureUpload).not.toHaveBeenCalled();
  });

  it.each([
    ["S3 が拒んだ", () => fetchMock.mockResolvedValue(new Response(null, { status: 403 }))],
    ["通信に失敗した", () => fetchMock.mockRejectedValue(new TypeError("Failed to fetch"))],
  ])("%sときは、完了を伝えずに理由を返す", async (_, arrange) => {
    arrange();

    const error = await uploadImage("demo", png()).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.problem.detail).toBe("画像を上げられませんでした。時間をおいてもう一度お試しください");
    expect(completeFigureUpload).not.toHaveBeenCalled();
  });
});
