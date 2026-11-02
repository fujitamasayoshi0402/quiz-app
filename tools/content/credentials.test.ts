import { test } from "node:test";
import assert from "node:assert/strict";
import { Credentials, type LoginConfig } from "./credentials.ts";
import type { CredentialStore } from "./keychain.ts";

class MemoryStore implements CredentialStore {
  values = new Map<string, string>();
  read(profile: string) {
    return this.values.get(profile);
  }
  write(profile: string, value: string) {
    this.values.set(profile, value);
  }
  delete(profile: string) {
    this.values.delete(profile);
  }
}
const config: LoginConfig = {
  region: "ap-northeast-1",
  clientId: "testclient",
  apiOrigin: "https://api.example.com",
  email: "admin@example.com",
};
const result = (access = "access-one", refresh: string | undefined = "refresh-one") =>
  Response.json({ AuthenticationResult: { AccessToken: access, RefreshToken: refresh, ExpiresIn: 3600 } });
function fixture() {
  const store = new MemoryStore();
  let time = 1_000_000;
  let respond: () => Promise<Response> = async () => result();
  const calls: { url: string; action: string; body: Record<string, unknown> }[] = [];
  const request = (async (url, options) => {
    calls.push({
      url: String(url),
      action: new Headers(options?.headers).get("X-Amz-Target")!.split(".").at(-1)!,
      body: JSON.parse(String(options?.body)),
    });
    return respond();
  }) as typeof fetch;
  const auth = new Credentials(store, request, () => time);
  return {
    store,
    auth,
    calls,
    expire: () => {
      time += 3600_000;
    },
    respond: (fn: () => Promise<Response>) => {
      respond = fn;
    },
  };
}

test("ログイン後は有効なトークンを使い、パスワードを保存しない。接続先と認証を環境ごとに分ける", async () => {
  const f = fixture();
  await f.auth.login("dev", config, "private-password");
  f.respond(async () => result("prod-access", "prod-refresh"));
  await f.auth.login(
    "prod",
    { ...config, apiOrigin: "https://prod.example.com", clientId: "prodclient" },
    "other-password",
  );
  assert.deepEqual(await f.auth.access("dev"), { apiOrigin: config.apiOrigin, accessToken: "access-one" });
  assert.deepEqual(await f.auth.access("prod"), { apiOrigin: "https://prod.example.com", accessToken: "prod-access" });
  assert.equal(f.calls.length, 2);
  assert(!f.store.read("dev")!.includes("private-password"));
  assert.deepEqual(Object.keys(f.auth.status("dev")!).sort(), ["apiOrigin", "email", "expiresAt"]);
});

for (const rotated of [false, true])
  test(`期限切れのトークンを更新する（ローテーション ${rotated}）`, async () => {
    const f = fixture();
    await f.auth.login("dev", config, "password");
    f.expire();
    f.respond(async () => result("access-two", rotated ? "refresh-two" : undefined));
    // result の既定値を避け、更新時に RefreshToken がない応答をそのまま作る。
    if (!rotated)
      f.respond(async () => Response.json({ AuthenticationResult: { AccessToken: "access-two", ExpiresIn: 3600 } }));
    assert.equal((await f.auth.access("dev")).accessToken, "access-two");
    assert.deepEqual(f.calls[1], {
      url: "https://cognito-idp.ap-northeast-1.amazonaws.com/",
      action: "GetTokensFromRefreshToken",
      body: { ClientId: "testclient", RefreshToken: "refresh-one" },
    });
    assert.equal(JSON.parse(f.store.read("dev")!).refreshToken, rotated ? "refresh-two" : "refresh-one");
    await f.auth.access("dev");
    assert.equal(f.calls.length, 2);
  });

test("Cognito が更新トークンを拒んだときだけ、その環境の状態を消す", async () => {
  const f = fixture();
  await f.auth.login("dev", config, "password");
  await f.auth.login("prod", config, "password");
  f.expire();
  f.respond(async () =>
    Response.json({ __type: "NotAuthorizedException", message: "must-not-appear" }, { status: 400 }),
  );
  await assert.rejects(f.auth.access("dev"), /期限切れか失効/);
  assert.equal(f.store.read("dev"), undefined);
  assert(f.store.read("prod"));
});

for (const failure of ["network", "429", "500", "invalid-json"])
  test(`一時的な失敗では保存済み認証を捨てない（${failure}）`, async () => {
    const f = fixture();
    await f.auth.login("dev", config, "password");
    f.expire();
    const before = f.store.read("dev");
    f.respond(async () => {
      if (failure === "network") throw new Error("secret-in-network-error");
      if (failure === "invalid-json") return new Response("secret-invalid-body");
      return Response.json({ message: "secret-provider-error" }, { status: Number(failure) });
    });
    await assert.rejects(f.auth.access("dev"), (error: Error) => !error.message.includes("secret"));
    assert.equal(f.store.read("dev"), before);
  });

test("パスワード認証の失敗や追加チャレンジで、前の認証を上書きしない", async () => {
  const f = fixture();
  await f.auth.login("dev", config, "password");
  const before = f.store.read("dev");
  f.respond(async () => Response.json({ ChallengeName: "SMS_MFA", Session: "secret-challenge" }));
  await assert.rejects(f.auth.login("dev", config, "wrong"), /追加の認証/);
  assert.equal(f.store.read("dev"), before);
});

test("logout は更新トークンを失効させてから、その環境の認証を消す", async () => {
  const f = fixture();
  await f.auth.login("dev", config, "password");
  await f.auth.login("prod", config, "password");
  f.respond(async () => new Response(null, { status: 200 }));
  await f.auth.logout("dev");
  assert.deepEqual(f.calls.at(-1)?.body, { ClientId: "testclient", Token: "refresh-one" });
  assert.equal(f.calls.at(-1)?.action, "RevokeToken");
  assert.equal(f.store.read("dev"), undefined);
  assert(f.store.read("prod"));
});

test("失効APIの通信失敗は、ログアウト成功とせず再試行できるよう残す", async () => {
  const f = fixture();
  await f.auth.login("dev", config, "password");
  f.respond(async () => {
    throw new Error("network");
  });
  await assert.rejects(f.auth.logout("dev"), /通信できません/);
  assert(f.store.read("dev"));
});

test("送信先の不正な設定と不正なプロファイル名を、認証情報を送る前に拒否する", async () => {
  const f = fixture();
  for (const apiOrigin of [
    "http://api.example.com",
    "https://name:password@api.example.com",
    "https://api.example.com/path",
  ]) {
    await assert.rejects(f.auth.login("dev", { ...config, apiOrigin }, "password"));
  }
  await assert.rejects(f.auth.login("../prod", config, "password"));
  await assert.rejects(f.auth.login("dev", { ...config, region: "host.example.com/" }, "password"));
  assert.equal(f.calls.length, 0);
});
