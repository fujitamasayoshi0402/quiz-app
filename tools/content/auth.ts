import { spawnSync } from "node:child_process";
import { parseArgs } from "node:util";
import { Credentials, validateConfig } from "./credentials.ts";
import { Keychain, validateProfile } from "./keychain.ts";

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      profile: { type: "string" },
      region: { type: "string" },
      "client-id": { type: "string" },
      api: { type: "string" },
      email: { type: "string" },
    },
  });
  const [command] = positionals;
  if (positionals.length !== 1 || !["login", "status", "logout"].includes(command) || !values.profile) {
    throw new Error(
      "使い方: node tools/content/auth.ts login|status|logout --profile <名前>\nlogin: --region <リージョン> --client-id <公開クライアントID> --api <APIオリジン> --email <メールアドレス>",
    );
  }
  validateProfile(values.profile);
  const keychain = new Keychain();
  const auth = new Credentials(keychain);
  if (command === "login") {
    const config = {
      region: values.region ?? "",
      clientId: values["client-id"] ?? "",
      apiOrigin: values.api ?? "",
      email: values.email ?? "",
    };
    validateConfig(config);
    // 入力を受ける前にコンパイル・キーチェーンのアクセス許可を確かめる。
    keychain.read(values.profile);
    // bash の read は tty のエコーを止める。コマンドは固定で、秘密を引数に埋め込まない。
    const input = spawnSync(
      "/bin/bash",
      [
        "-c",
        'set -e; read -r -s -p "Cognito password: " content_password < /dev/tty; printf "\\n" > /dev/tty; printf "%s" "$content_password"',
      ],
      { encoding: "utf8", stdio: ["inherit", "pipe", "inherit"] },
    );
    if (input.status !== 0 || !input.stdout)
      throw new Error("パスワードを入力できませんでした。対話ターミナルで実行してください");
    try {
      await auth.login(values.profile, config, input.stdout);
    } finally {
      input.stdout = "";
    }
    console.log(`${values.profile}: ログイン状態をキーチェーンに保存しました。パスワードは保存していません`);
  } else if (command === "logout") {
    await auth.logout(values.profile);
    console.log(`${values.profile}: 更新トークンを失効させ、保存済み認証を削除しました`);
  } else {
    const status = auth.status(values.profile);
    console.log(
      status
        ? `${values.profile}: ${status.email} / ${status.apiOrigin}\nアクセストークン期限: ${new Date(status.expiresAt).toISOString()}（期限後は利用時に自動更新）`
        : `${values.profile}: 保存済み認証なし`,
    );
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "認証処理に失敗しました");
  process.exitCode = 1;
});
