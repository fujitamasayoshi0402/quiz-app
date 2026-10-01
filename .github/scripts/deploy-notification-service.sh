#!/usr/bin/env bash
# notification-service（Lambda）のコードを載せ替える。GitHub Actions（deploy-dev.yml）から呼ぶ。手元からも同じ手順で流せる。
#
#   deploy-notification-service.sh <関数名> <zip> <コミット>
#
# 1. zip を関数に載せ、切り替わるのを待つ
# 2. 知らない種類のイベントで 1 度呼び、起動できるかを確かめる。何もせずに終わるため、Slack には送らない
# 3. 載せたコミットを関数のタグ（DeployedCommit）に書く。次のデプロイが、これと比べて載せるかを決める（deployed-commits.sh）
#
# 関数の形（ロール、環境変数、メモリなど）は Terraform が持つ。ここではコードだけを替える（開発ガイドライン「通知（notification-service）」）。
# 確かめるのに失敗したら、タグは書かずに止める。次のデプロイが、直したものを載せ直す
set -euo pipefail

FUNCTION=$1
ZIP=$2
COMMIT=$3

TAG_KEY=DeployedCommit

ARN=$(aws lambda update-function-code --function-name "$FUNCTION" --zip-file "fileb://$ZIP" \
  --query FunctionArn --output text)
echo "コードを載せました。切り替わるのを待ちます"
aws lambda wait function-updated --function-name "$FUNCTION"

# EventBridge と同じ封筒の形で、知らない種類のイベントを送る。Handler は種類を見て何もせずに返す。
# 環境変数の欠け、クラスの読み込み、AWS のクライアントの初期化に失敗していれば、ここで分かる
RESPONSE=$(mktemp)
ERROR=$(aws lambda invoke --function-name "$FUNCTION" \
  --cli-binary-format raw-in-base64-out \
  --payload '{"detail-type":"DeployCheck","detail":{}}' \
  --query FunctionError --output text "$RESPONSE")
if [[ "$ERROR" != "None" ]]; then
  echo "::error::載せた関数を呼ぶと失敗しました（${ERROR}）。CloudWatch Logs の /aws/lambda/${FUNCTION} を見てください"
  # 応答はエラーの種類とメッセージ。Webhook の URL は含まない（Notifier はログにも例外にも出さない）
  cat "$RESPONSE"
  exit 1
fi
echo "載せた関数を呼び、起動できることを確かめました"

aws lambda tag-resource --resource "$ARN" --tags "${TAG_KEY}=${COMMIT}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "- notification-service: ${COMMIT::12} を載せました" >> "$GITHUB_STEP_SUMMARY"
fi
