import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, createNonce, originsFrom } from "./content-security-policy";

function directives(policy: string): Map<string, string[]> {
  return new Map(
    policy.split("; ").map((directive) => {
      const [name, ...values] = directive.split(" ");
      return [name, values];
    }),
  );
}

describe("contentSecurityPolicy", () => {
  const base = { nonce: "abc", development: false, imageOrigins: [], connectOrigins: [] };

  it("スクリプトは nonce を付けたものだけを許す", () => {
    const policy = directives(contentSecurityPolicy(base));
    expect(policy.get("script-src")).toEqual(["'self'", "'nonce-abc'", "'strict-dynamic'"]);
    expect(policy.get("object-src")).toEqual(["'none'"]);
    expect(policy.get("frame-ancestors")).toEqual(["'none'"]);
  });

  it("解説図の CDN と、アップロードの送り先を加える", () => {
    const policy = directives(
      contentSecurityPolicy({
        ...base,
        imageOrigins: ["https://figures.example.com"],
        connectOrigins: ["https://bucket.s3.ap-northeast-1.amazonaws.com"],
      }),
    );
    expect(policy.get("img-src")).toContain("https://figures.example.com");
    expect(policy.get("connect-src")).toContain("https://bucket.s3.ap-northeast-1.amazonaws.com");
    expect(policy.get("frame-src")).toEqual(["https://embed.diagrams.net"]);
  });

  it("開発用のサーバーでだけ、eval と WebSocket を許す", () => {
    expect(directives(contentSecurityPolicy(base)).get("script-src")).not.toContain("'unsafe-eval'");
    const development = directives(contentSecurityPolicy({ ...base, development: true }));
    expect(development.get("script-src")).toContain("'unsafe-eval'");
    expect(development.get("connect-src")).toContain("ws:");
  });
});

describe("originsFrom", () => {
  it("空白で区切った並びを配列にする", () => {
    expect(originsFrom(" https://a.example  https://b.example ")).toEqual(["https://a.example", "https://b.example"]);
    expect(originsFrom(undefined)).toEqual([]);
    expect(originsFrom("")).toEqual([]);
  });
});

describe("createNonce", () => {
  it("要求ごとに違う値を作る", () => {
    expect(createNonce()).not.toBe(createNonce());
    expect(createNonce()).toMatch(/^[A-Za-z0-9+/]{22}==$/);
  });
});
