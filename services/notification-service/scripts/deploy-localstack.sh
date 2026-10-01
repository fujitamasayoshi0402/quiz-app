#!/bin/sh
# ローカルの LocalStack に notification-service を載せる（開発ガイドライン「通知（notification-service）」）。
# AWS では Terraform（modules/notification-service）が作るものを、同じ名前の付け方で作る。
#
#   docker compose up -d localstack
#   ./gradlew :services:notification-service:buildZip
#   services/notification-service/scripts/deploy-localstack.sh
#
# 既定では Slack へは送らず、送る内容を Lambda のログに出す（SLACK_DELIVERY=log）。
# 本当に送るときは SLACK_DELIVERY=send を付けて流す。LocalStack は再起動で中身が消えるため、そのたびに流す
set -eu

cd "$(dirname "$0")/.."

ENDPOINT="http://localhost:${LOCALSTACK_PORT:-4566}"
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=ap-northeast-1
awslocal() { aws --endpoint-url "$ENDPOINT" "$@"; }

NAME=quiz-app-local-notification-service
TABLE=quiz-app-local-notification-deliveries
BUS=quiz-app-local
RULE=quiz-app-local-notify-slack
ZIP=build/distributions/notification-service.zip
PATTERN=../../infra/terraform/modules/notification-service/event-pattern.json

if [ ! -f "$ZIP" ]; then
  echo "$ZIP がありません。./gradlew :services:notification-service:buildZip で作ってください" >&2
  exit 1
fi

if ! awslocal dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  awslocal dynamodb create-table --table-name "$TABLE" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=eventId,KeyType=HASH >/dev/null
fi

# SSM は quiz-service と同じ頭（application.yml の app.notifications.parameter-prefix の既定）
ENVIRONMENT="Variables={WEBHOOK_PARAMETER_PREFIX=/quiz-app/local,DELIVERIES_TABLE=$TABLE,WEB_BASE_URL=http://localhost:${WEB_PORT:-3000},SLACK_DELIVERY=${SLACK_DELIVERY:-log}}"

if awslocal lambda get-function --function-name "$NAME" >/dev/null 2>&1; then
  awslocal lambda update-function-code --function-name "$NAME" --zip-file "fileb://$ZIP" >/dev/null
  awslocal lambda wait function-updated-v2 --function-name "$NAME"
  awslocal lambda update-function-configuration --function-name "$NAME" --environment "$ENVIRONMENT" >/dev/null
  awslocal lambda wait function-updated-v2 --function-name "$NAME"
else
  # LocalStack はロールを確かめない
  awslocal lambda create-function --function-name "$NAME" \
    --runtime java21 --handler com.quizapp.notification.Handler::handleRequest \
    --role arn:aws:iam::000000000000:role/notification-service \
    --memory-size 512 --timeout 30 \
    --environment "$ENVIRONMENT" \
    --zip-file "fileb://$ZIP" >/dev/null
  awslocal lambda wait function-active-v2 --function-name "$NAME"
fi

RULE_ARN=$(awslocal events put-rule --name "$RULE" --event-bus-name "$BUS" \
  --event-pattern "file://$PATTERN" --query RuleArn --output text)
FUNCTION_ARN=$(awslocal lambda get-function --function-name "$NAME" --query Configuration.FunctionArn --output text)
awslocal events put-targets --rule "$RULE" --event-bus-name "$BUS" --targets "Id=notification-service,Arn=$FUNCTION_ARN" >/dev/null
awslocal lambda add-permission --function-name "$NAME" --statement-id AllowQuizEventsRule \
  --action lambda:InvokeFunction --principal events.amazonaws.com --source-arn "$RULE_ARN" >/dev/null 2>&1 || true

echo "載せました: ${NAME}（SLACK_DELIVERY=${SLACK_DELIVERY:-log}）"
echo "ログ: aws --endpoint-url $ENDPOINT logs tail /aws/lambda/$NAME --follow"
