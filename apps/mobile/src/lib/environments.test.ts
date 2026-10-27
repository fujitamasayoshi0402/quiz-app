import { describe, expect, it } from "vitest";
import { environmentsFrom, pickEnvironment } from "./environments";

const dev = {
  apiOrigin: "https://api.dev.example.com/",
  authIssuer: "https://cognito-idp.ap-northeast-1.amazonaws.com/dev",
  authClientId: "dev-client",
};
const prod = {
  apiOrigin: "https://api.quiz.example.com",
  authIssuer: "https://cognito-idp.ap-northeast-1.amazonaws.com/prod",
  authClientId: "prod-client",
};

describe("environmentsFrom", () => {
  it("両方を渡せば、prod、dev の順に並べる", () => {
    const environments = environmentsFrom({ dev, prod });
    expect(environments.map((e) => e.name)).toEqual(["prod", "dev"]);
  });

  it("末尾の / を落とす。API の呼び出しでパスを足すため", () => {
    const [environment] = environmentsFrom({ dev, prod: {} });
    expect(environment.apiOrigin).toBe("https://api.dev.example.com");
  });

  it("値がすべて空の環境は、一覧に入れない", () => {
    expect(
      environmentsFrom({ dev, prod: { apiOrigin: "", authIssuer: "", authClientId: "" } }).map((e) => e.name),
    ).toEqual(["dev"]);
  });

  it("一部だけ埋まっていれば、足りない変数の名前を出して落とす", () => {
    expect(() => environmentsFrom({ dev, prod: { apiOrigin: prod.apiOrigin } })).toThrow(
      "EXPO_PUBLIC_PROD_AUTH_ISSUER、EXPO_PUBLIC_PROD_AUTH_CLIENT_ID が設定されていません",
    );
  });

  it("どの環境も無ければ落とす", () => {
    expect(() => environmentsFrom({ dev: {}, prod: {} })).toThrow("つなぐ先が設定されていません");
  });
});

describe("pickEnvironment", () => {
  const environments = environmentsFrom({ dev, prod });

  it("前に選んだものを使う", () => {
    expect(pickEnvironment(environments, "dev").name).toBe("dev");
  });

  it("選んでいなければ、一覧の最初を使う", () => {
    expect(pickEnvironment(environments, null).name).toBe("prod");
  });

  it("前に選んだものがこのビルドに無ければ、一覧の最初を使う", () => {
    expect(pickEnvironment(environmentsFrom({ dev, prod: {} }), "prod").name).toBe("dev");
  });
});
