import { describe, expect, it } from "vitest";
import { isSameOriginRequest, safeReturnTo } from "./request";

describe("safeReturnTo", () => {
  it("アプリの中のパスは、クエリとともにそのまま返す", () => {
    expect(safeReturnTo("/t/demo/play?category=1")).toBe("/t/demo/play?category=1");
    expect(safeReturnTo("/invitations/abc")).toBe("/invitations/abc");
  });

  it("無いときと、パスでないときはトップに戻す", () => {
    expect(safeReturnTo(undefined)).toBe("/");
    expect(safeReturnTo(null)).toBe("/");
    expect(safeReturnTo("")).toBe("/");
    expect(safeReturnTo("https://evil.example/")).toBe("/");
    expect(safeReturnTo("javascript:alert(1)")).toBe("/");
  });

  it("外部のオリジンに解けるものは通さない", () => {
    // どれもブラウザの URL の解釈では、外部のオリジンになる
    for (const value of [
      "//evil.example",
      "/\\evil.example",
      "/\t/evil.example",
      "/\n/evil.example",
      "/\r\\evil.example",
    ]) {
      expect(safeReturnTo(value)).toBe("/");
    }
  });
});

describe("isSameOriginRequest", () => {
  const appOrigin = "https://dev.example.com";
  const request = (method: string, origin: string | null, secFetchSite: string | null = null) =>
    isSameOriginRequest({ method, origin, secFetchSite, appOrigin });

  it("状態を変えない方法は、送り元を問わない", () => {
    for (const method of ["GET", "HEAD", "OPTIONS", "get"]) {
      expect(request(method, "https://evil.example", "cross-site")).toBe(true);
    }
  });

  it("状態を変える方法は、アプリのオリジンからだけ通す", () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE", "post"]) {
      expect(request(method, appOrigin)).toBe(true);
      expect(request(method, "https://evil.example")).toBe(false);
    }
  });

  it("同じサイトの別のサブドメインと、スキームやポートの違うものは通さない", () => {
    for (const origin of [
      "https://other.example.com",
      "https://example.com",
      "http://dev.example.com",
      "https://dev.example.com:8443",
      "null",
    ]) {
      expect(request("POST", origin)).toBe(false);
    }
  });

  it("Origin が無いときは、Sec-Fetch-Site で決める", () => {
    expect(request("POST", null, "same-origin")).toBe(true);
    expect(request("POST", null, "none")).toBe(true);
    expect(request("POST", null, "same-site")).toBe(false);
    expect(request("POST", null, "cross-site")).toBe(false);
  });

  it("どちらも無い要求は、ブラウザからではないとして通す", () => {
    expect(request("POST", null, null)).toBe(true);
  });
});
