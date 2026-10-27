import { EncryptJWT } from "jose";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** 鍵はモジュールに残るので、テストごとに読み直す */
async function load() {
  vi.resetModules();
  return import("./token");
}

const card = { label: "世界の地理・基礎", correct: 8, total: 10 };

describe("共有のカードのトークン", () => {
  beforeEach(() => vi.stubEnv("AUTH_SESSION_SECRET", "session-secret-for-tests"));
  afterEach(() => vi.unstubAllEnvs());

  it("作ったトークンから、同じカードを読める", async () => {
    const { sealShareCard, openShareCard } = await load();

    expect(await openShareCard(await sealShareCard(card))).toEqual(card);
  });

  it("書き換えたトークンは読めない", async () => {
    const { sealShareCard, openShareCard } = await load();
    const token = await sealShareCard(card);
    const tampered = `${token.slice(0, -4)}${token.slice(-4) === "AAAA" ? "BBBB" : "AAAA"}`;

    expect(await openShareCard(tampered)).toBeUndefined();
    expect(await openShareCard("not-a-token")).toBeUndefined();
  });

  it("秘密を入れ替えると、それまでのトークンは読めない", async () => {
    const token = await (await load()).sealShareCard(card);
    vi.stubEnv("AUTH_SESSION_SECRET", "rotated-secret");

    expect(await (await load()).openShareCard(token)).toBeUndefined();
  });

  it("ログインのセッションと同じ鍵では作らない（セッションの Cookie を共有のトークンとして読ませない）", async () => {
    const { openShareCard } = await load();
    const sessionKey = new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode("session-secret-for-tests")),
    );
    const sealedWithSessionKey = await new EncryptJWT(card)
      .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
      .encrypt(sessionKey);

    expect(await openShareCard(sealedWithSessionKey)).toBeUndefined();
  });

  it("問題数より正解が多いカードは作れない形として捨てる", async () => {
    const { sealShareCard, openShareCard } = await load();

    expect(await openShareCard(await sealShareCard({ ...card, correct: 11 }))).toBeUndefined();
  });

  it("正答率は四捨五入した %", async () => {
    const { rateOf } = await load();

    expect(rateOf({ label: "x", correct: 2, total: 3 })).toBe(67);
    expect(rateOf({ label: "x", correct: 0, total: 5 })).toBe(0);
  });
});
