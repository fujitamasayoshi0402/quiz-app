import { spawnSync } from "node:child_process";
import { mkdirSync, statSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

export interface CredentialStore {
  read(profile: string): string | undefined;
  write(profile: string, value: string): void;
  delete(profile: string): void;
}

export function validateProfile(profile: string): void {
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(profile)) {
    throw new Error("プロファイル名は小文字・数字・ハイフンで指定してください（64文字まで）");
  }
}

/** Swift は OS の Security API を呼ぶためだけに使う。バイナリとコンパイルキャッシュに秘密は含まない。 */
export class Keychain implements CredentialStore {
  private invoke(operation: string, profile: string, value?: string): string | undefined {
    validateProfile(profile);
    if (process.platform !== "darwin") throw new Error("保存済み認証は macOS のキーチェーンを使います");
    const source = fileURLToPath(new URL("./keychain.swift", import.meta.url));
    const dir = fileURLToPath(new URL("./tmp/", import.meta.url));
    const executable = `${dir}content-keychain`;
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    if (!existsSync(executable) || statSync(executable).mtimeMs < statSync(source).mtimeMs) {
      const result = spawnSync("/usr/bin/xcrun", [
        "swiftc",
        "-module-cache-path",
        `${dir}swift-cache`,
        source,
        "-o",
        executable,
      ]);
      if (result.status !== 0)
        throw new Error("キーチェーン用ツールを作れません。Xcode Command Line Tools を確認してください");
    }
    const result = spawnSync(executable, [], {
      input: JSON.stringify({ operation, profile, value }),
      encoding: "utf8",
      maxBuffer: 1024 * 1024,
    });
    // 子プロセスの stdout、stderr、例外には秘密が含まれうるため、そのまま出さない。
    if (result.status !== 0) throw new Error("キーチェーン用ツールの実行に失敗しました");
    let reply: { status: number; value?: string };
    try {
      reply = JSON.parse(result.stdout);
    } catch {
      throw new Error("キーチェーンから応答を読めませんでした");
    }
    if (reply.status === -25300 && operation !== "write") return undefined;
    if (reply.status !== 0)
      throw new Error(
        `キーチェーンを利用できません（OSStatus ${reply.status}）。ロックやアクセス許可を確認してください`,
      );
    return reply.value;
  }

  read(profile: string) {
    return this.invoke("read", profile);
  }
  write(profile: string, value: string) {
    this.invoke("write", profile, value);
  }
  delete(profile: string) {
    this.invoke("delete", profile);
  }
}
