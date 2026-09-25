#!/usr/bin/env bash
# スモークテストの利用者でアクセストークンを取り、標準出力に書く。GitHub Actions（deploy-dev.yml）と手元の両方から使う。
#
#   AUTH_CLIENT_SECRET=... SMOKE_USER_PASSWORD=... smoke-token.sh <web のクライアント ID> <メールアドレス>
#
# シークレットとパスワードは環境変数で渡す。引数にすると、プロセスの一覧から見える。
# 値は Terraform の出力にある（envs/dev の auth_client_secret、smoke_user_password）。
set -euo pipefail

CLIENT_ID=$1
USERNAME=$2
: "${AUTH_CLIENT_SECRET:?AUTH_CLIENT_SECRET を渡してください}"
: "${SMOKE_USER_PASSWORD:?SMOKE_USER_PASSWORD を渡してください}"

# web のクライアントはシークレットを持つため、要求に SECRET_HASH（利用者名 + クライアント ID の HMAC-SHA256）を添える
SECRET_HASH=$(printf '%s' "${USERNAME}${CLIENT_ID}" | openssl dgst -sha256 -hmac "$AUTH_CLIENT_SECRET" -binary | base64)

# 画面と同じ USER_AUTH で、パスワードを指定してサインインする。
# InitiateAuth は AWS の認証情報を使わない API なので、署名しない（手元で SSO が切れていても取れる）
aws cognito-idp initiate-auth --no-sign-request \
  --client-id "$CLIENT_ID" \
  --auth-flow USER_AUTH \
  --auth-parameters "$(jq -n \
    --arg user "$USERNAME" --arg password "$SMOKE_USER_PASSWORD" --arg hash "$SECRET_HASH" \
    '{USERNAME: $user, PASSWORD: $password, SECRET_HASH: $hash, PREFERRED_CHALLENGE: "PASSWORD"}')" \
  --query AuthenticationResult.AccessToken \
  --output text
