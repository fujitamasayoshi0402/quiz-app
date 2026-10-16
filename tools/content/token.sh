#!/usr/bin/env bash
# 流し込みに使うアクセストークンを、自分の Cognito の利用者で取る（ADR-0034、DEV-186）。標準出力に出す。
#
#   cd infra/terraform/envs/dev
#   export ACCESS_TOKEN=$(../../../../tools/content/token.sh <メールアドレス>)
#
# パスワードは画面に出さずに受ける。トークンはファイルに残さない（期限は 1 時間）。
# 取り方はスモークテストと同じ（.github/scripts/smoke-token.sh。InitiateAuth）。dev の Terraform の出力から、web のクライアントを読む
set -euo pipefail

EMAIL=${1:?メールアドレスを渡してください}
HERE=$(cd "$(dirname "$0")" && pwd)

read -r -s -p "${EMAIL} のパスワード: " PASSWORD < /dev/tty
echo > /dev/tty

AUTH_CLIENT_SECRET="$(terraform output -raw auth_client_secret)" SMOKE_USER_PASSWORD="$PASSWORD" \
  "$HERE/../../.github/scripts/smoke-token.sh" "$(terraform output -raw auth_client_id)" "$EMAIL"
