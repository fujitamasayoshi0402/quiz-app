import { describe, expect, it } from "vitest";
import { needsRefresh, parseSession, sessionFrom } from "./session";

describe("needsRefresh", () => {
  const session = { accessToken: "a", refreshToken: "r", expiresAt: 10_000 };

  it("期限まで 5 分以上あれば、更新しない", () => {
    expect(needsRefresh(session, 10_000 - 5 * 60)).toBe(false);
  });

  it("期限まで 5 分を切ったら、更新する", () => {
    expect(needsRefresh(session, 10_000 - 5 * 60 + 1)).toBe(true);
  });

  it("期限を過ぎていれば、更新する", () => {
    expect(needsRefresh(session, 10_001)).toBe(true);
  });
});

describe("sessionFrom", () => {
  it("ログインの応答から作る", () => {
    expect(sessionFrom({ accessToken: "a", refreshToken: "r", expiresIn: 3600, issuedAt: 100 })).toEqual({
      accessToken: "a",
      refreshToken: "r",
      expiresAt: 3700,
    });
  });

  it("更新の応答にリフレッシュトークンがなければ、前のものを使い続ける", () => {
    const previous = { accessToken: "old", refreshToken: "r", expiresAt: 0 };
    expect(sessionFrom({ accessToken: "new", expiresIn: 3600, issuedAt: 100 }, previous)?.refreshToken).toBe("r");
  });

  it("リフレッシュトークンがどこにもなければ、ログインしていない扱いにする", () => {
    expect(sessionFrom({ accessToken: "a", expiresIn: 3600, issuedAt: 100 })).toBeNull();
  });

  it("期限が書かれていなければ、すぐに更新する扱いにする", () => {
    expect(sessionFrom({ accessToken: "a", refreshToken: "r", issuedAt: 100 })?.expiresAt).toBe(100);
  });
});

describe("parseSession", () => {
  it("書いたものを読める", () => {
    const session = { accessToken: "a", refreshToken: "r", expiresAt: 1 };
    expect(parseSession(JSON.stringify(session))).toEqual(session);
  });

  it.each([null, "", "{", "[]", JSON.stringify({ accessToken: "a" })])("形が違えば null（%s）", (raw) => {
    expect(parseSession(raw)).toBeNull();
  });
});
