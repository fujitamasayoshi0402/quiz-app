import { execFileSync } from "node:child_process";
import { createHmac } from "node:crypto";
import path from "node:path";
import { runSql } from "./database";
import { password, users } from "./users";

const repositoryRoot = path.resolve(__dirname, "../../..");

/**
 * デモのテナントに、デモのパック（`content/demo/`）を流し込む（ADR-0034）。
 *
 * 見に来た人が見るものと同じデモで、E2E と画面の撮影を流すため。シードはデモのクイズを入れない。
 * 流すのは、ほかの環境と同じスクリプト（`tools/content/load.ts`）。パックと違うクイズは直し、パックにないクイズは消す。
 *
 * 流す間だけ、E2E の管理者をデモのテナントの管理者にする。所属を残すと所属が 2 つになり、
 * 「所属が 1 つならテナントへ直接移る」を確かめるテスト（`roles.spec.ts`）が前提を失う
 */
export async function loadDemoPack(baseURL: string) {
  // ローカルでは、docker compose と同じ .env から Cognito のクライアントを読む。CI はジョブの環境変数で渡す
  try {
    process.loadEnvFile(path.join(repositoryRoot, ".env"));
  } catch {
    // .env がなければ、環境変数だけを使う
  }

  const token = await accessToken(users.admin);
  runSql(membership("INSERT"));
  try {
    execFileSync(
      process.execPath,
      ["tools/content/load.ts", "content/demo", "--api", baseURL, "--tenant", "demo", "--update", "--prune"],
      { cwd: repositoryRoot, env: { ...process.env, ACCESS_TOKEN: token }, stdio: "inherit" },
    );
  } finally {
    runSql(membership("DELETE"));
  }
}

function membership(action: "INSERT" | "DELETE") {
  if (action === "INSERT") {
    return `
      INSERT INTO core.tenant_members (tenant_id, user_id, role)
      SELECT t.id, u.id, 'admin' FROM core.users u JOIN core.tenants t ON t.slug = 'demo' AND t.deleted_at IS NULL
      WHERE lower(u.email) = '${users.admin}'
      ON CONFLICT (tenant_id, user_id) WHERE deleted_at IS NULL DO NOTHING;`;
  }
  return `
    UPDATE core.tenant_members m SET deleted_at = now()
    FROM core.users u, core.tenants t
    WHERE m.user_id = u.id AND m.tenant_id = t.id AND t.slug = 'demo' AND m.deleted_at IS NULL
      AND lower(u.email) = '${users.admin}';`;
}

/**
 * Cognito の InitiateAuth で、パスワードからアクセストークンを取る。取り方はスモークテストと同じ（`.github/scripts/smoke-token.sh`）。
 * InitiateAuth は AWS の認証情報を使わない API なので、署名しない
 */
async function accessToken(email: string): Promise<string> {
  const issuer = required("AUTH_ISSUER");
  const clientId = required("AUTH_CLIENT_ID");
  const clientSecret = required("AUTH_CLIENT_SECRET");
  // 発行者は https://cognito-idp.<リージョン>.amazonaws.com/<User Pool の ID>
  const endpoint = new URL(issuer).origin;

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-amz-json-1.1",
      "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth",
    },
    body: JSON.stringify({
      ClientId: clientId,
      AuthFlow: "USER_AUTH",
      AuthParameters: {
        USERNAME: email,
        PASSWORD: password(),
        // web のクライアントはシークレットを持つため、利用者名 + クライアント ID の HMAC-SHA256 を添える
        SECRET_HASH: createHmac("sha256", clientSecret).update(email + clientId).digest("base64"),
        PREFERRED_CHALLENGE: "PASSWORD",
      },
    }),
  });
  const body = (await response.json()) as { AuthenticationResult?: { AccessToken?: string }; __type?: string };
  const token = body.AuthenticationResult?.AccessToken;
  // 応答の本文は出さない。失敗の種類（__type）だけで足りる
  if (!response.ok || !token) throw new Error(`${email} のアクセストークンを取れませんでした（${response.status} ${body.__type ?? ""}）`);
  return token;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} を渡してください（.env か環境変数。値は terraform output -raw ${name.toLowerCase()}）`);
  return value;
}
