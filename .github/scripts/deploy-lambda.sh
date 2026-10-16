#!/usr/bin/env bash
# Lambda（notification-service、news-quiz-drafter）のコードを載せ替える。GitHub Actions（deploy.yml）から呼ぶ。手元からも同じ手順で流せる。
#
#   deploy-lambda.sh <関数名> <zip> <コミット> <確かめる入力> <表示の名前>
#
# 1. zip を関数に載せ、切り替わるのを待つ
# 2. <確かめる入力> で 1 度呼び、起動できるかを確かめる。どちらの関数も、この入力では何もせずに終わる
#      notification-service … 知らない種類のイベント。Slack には送らない
#      news-quiz-drafter    … {"check":true}。Bedrock は呼ばない
# 3. 載せたコミットを関数のタグ（DeployedCommit）に書く。次のデプロイが、これと比べて載せるかを決める（deployed-commits.sh）
#
# 関数の形（ロール、環境変数、メモリなど）は Terraform が持つ。ここではコードだけを替える（ADR-0015）。
# 確かめるのに失敗したら、タグは書かずに止める。次のデプロイが、直したものを載せ直す
set -euo pipefail

FUNCTION=$1
ZIP=$2
COMMIT=$3
PAYLOAD=$4
LABEL=$5

TAG_KEY=DeployedCommit

ARN=$(aws lambda update-function-code --function-name "$FUNCTION" --zip-file "fileb://$ZIP" \
  --query FunctionArn --output text)
echo "${LABEL} のコードを載せました。切り替わるのを待ちます"
aws lambda wait function-updated --function-name "$FUNCTION"

# 環境変数の欠け、クラスの読み込み、AWS のクライアントの初期化に失敗していれば、ここで分かる
RESPONSE=$(mktemp)
ERROR=$(aws lambda invoke --function-name "$FUNCTION" \
  --cli-binary-format raw-in-base64-out \
  --payload "$PAYLOAD" \
  --query FunctionError --output text "$RESPONSE")
if [[ "$ERROR" != "None" ]]; then
  echo "::error::載せた ${LABEL} を呼ぶと失敗しました（${ERROR}）。CloudWatch Logs の /aws/lambda/${FUNCTION} を見てください"
  # 応答はエラーの種類とメッセージ。秘密は含まない（notification-service は Webhook の URL をログにも例外にも出さない）
  cat "$RESPONSE"
  exit 1
fi
echo "載せた ${LABEL} を呼び、起動できることを確かめました"

aws lambda tag-resource --resource "$ARN" --tags "${TAG_KEY}=${COMMIT}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "- ${LABEL}: ${COMMIT::12} を載せました" >> "$GITHUB_STEP_SUMMARY"
fi
