import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

// 接続先を上書きして、保存したトークンを別のサーバーへ送ることを防ぐ。
for (const conflict of ["api", "token"]) {
  test(`保存済み認証と ${conflict} の併用をキーチェーンに触れる前に拒否する`, () => {
    const env = { ...process.env };
    delete env.ACCESS_TOKEN;
    if (conflict === "token") env.ACCESS_TOKEN = "test-secret-not-to-log";
    const args = ["/missing-pack", "--profile", "dev", "--tenant", "demo"];
    if (conflict === "api") args.push("--api", "https://example.invalid");
    const result = spawnSync(process.execPath, [new URL("./load.ts", import.meta.url).pathname, ...args], {
      env,
      encoding: "utf8",
    });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /併用できません/);
    assert.doesNotMatch(result.stdout + result.stderr, /test-secret-not-to-log/);
  });
}
