import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Keychain } from "./keychain.ts";

// macOS の実キーチェーンは明示したときだけ検証する。CI の Linux は認証状態のテストを使う。
test(
  "キーチェーンで長い値の保存・別プロセスでの読出し・更新・削除を行う",
  {
    skip: process.platform !== "darwin" || process.env.TEST_CONTENT_KEYCHAIN !== "1",
  },
  () => {
    const profile = `test-${randomUUID()}`;
    const store = new Keychain();
    const value = JSON.stringify({ dummy: "テスト".repeat(5000) });
    try {
      assert.equal(store.read(profile), undefined);
      store.write(profile, value);
      assert.equal(new Keychain().read(profile), value);
      store.write(profile, "updated");
      assert.equal(new Keychain().read(profile), "updated");
      store.delete(profile);
      assert.equal(store.read(profile), undefined);
    } finally {
      store.delete(profile);
    }
  },
);
