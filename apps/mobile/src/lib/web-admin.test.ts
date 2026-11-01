import { describe, expect, it } from "vitest";
import type { Environment } from "./environments";
import { webAdminUrl } from "./web-admin";

const environment: Environment = {
  name: "dev",
  label: "開発（dev）",
  apiOrigin: "https://api.dev.example.com",
  authIssuer: "https://cognito.example.com/pool",
  authClientId: "client",
  webOrigin: "https://dev.example.com",
};

describe("webAdminUrl", () => {
  it("管理者なら、つなぐ先の web の、そのテナントの管理画面を開く", () => {
    expect(webAdminUrl(environment, "demo", "admin")).toBe("https://dev.example.com/t/demo/admin");
  });

  it("一般ユーザーには出さない", () => {
    expect(webAdminUrl(environment, "demo", "member")).toBeUndefined();
    expect(webAdminUrl(environment, "demo", undefined)).toBeUndefined();
  });

  it("つなぐ先に web の origin が無いビルドでは出さない", () => {
    expect(webAdminUrl({ ...environment, webOrigin: undefined }, "demo", "admin")).toBeUndefined();
  });
});
