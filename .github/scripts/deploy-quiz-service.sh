#!/usr/bin/env bash
# quiz-service を ECS にデプロイする。GitHub Actions（deploy-dev.yml）から呼ぶ。手元からも同じ手順で流せる。
#
#   deploy-quiz-service.sh <クラスタ> <サービス> <マイグレーションのタスク定義のファミリー> <イメージ>
#
# 1. マイグレーションのタスク定義に、新しいイメージのリビジョンを登録する
# 2. マイグレーションを単発のタスクとして流す。終了コードが 0 でなければ、サービスは替えずに止める
# 3. アプリのタスク定義に、新しいイメージのリビジョンを登録し、サービスを更新する。夜間の停止中なら 1 つ起動する
# 4. 入れ替えが終わるのを待つ。起動に失敗して前のリビジョンに戻ったら（サーキットブレーカー）、失敗にする
#
# タスク定義の形（環境変数、ロール、CPU など）は Terraform が持つ。ここでは最新のリビジョンのイメージだけを差し替える（ADR-0015）。
set -euo pipefail

CLUSTER=$1
SERVICE=$2
MIGRATE_FAMILY=$3
IMAGE=$4

CONTAINER=quiz-service
# ヘルスチェックの猶予（180 秒）と JVM の起動、古いタスクの切り離しを合わせても、通常は 5 分ほどで終わる。
# 起動に失敗して前のリビジョンに戻るときは 15 分ほどかかる（イメージを取れないとき。DEV-48 で測った）
ROLLOUT_TIMEOUT_SECONDS=1800

summary() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then echo "$1" >> "$GITHUB_STEP_SUMMARY"; fi
}

# 最新のリビジョンを読み、イメージだけを差し替えて登録する。登録したリビジョンの ARN を返す。
# 読み取り専用の項目は登録のときに渡せないため落とす。タグ（Project / Env）は引き継ぐ。
#
# $(...) の中では set -e が効かない（bash 4.4 未満の inherit_errexit がない環境を含む）。失敗は return で明示的に返す
register() {
  local family=$1 input arn
  input=$(mktemp)
  aws ecs describe-task-definition --task-definition "$family" --include TAGS --output json |
    jq --arg image "$IMAGE" --arg container "$CONTAINER" '
      .tags as $tags
      | .taskDefinition
      | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
            .registeredAt, .registeredBy, .deregisteredAt)
      | .containerDefinitions |= map(if .name == $container then .image = $image else . end)
      | if ($tags | length) > 0 then .tags = $tags else . end' > "$input" || return 1
  arn=$(aws ecs register-task-definition --cli-input-json "file://$input" \
    --query 'taskDefinition.taskDefinitionArn' --output text) || return 1
  rm -f "$input"
  echo "$arn"
}

# ---- マイグレーション ----

MIGRATE_ARN=$(register "$MIGRATE_FAMILY")
echo "マイグレーションのタスク定義: ${MIGRATE_ARN##*/}"

# サービスと同じサブネットとセキュリティグループで動かす。Aurora へ届く経路が同じになる
NETWORK=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].networkConfiguration' --output json)

TASK=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$MIGRATE_ARN" \
  --launch-type FARGATE --network-configuration "$NETWORK" \
  --propagate-tags TASK_DEFINITION --started-by deploy \
  --query 'tasks[0].taskArn' --output text)
if [[ "$TASK" == "None" || -z "$TASK" ]]; then
  echo "::error::マイグレーションのタスクを起動できませんでした"
  exit 1
fi
TASK_ID=${TASK##*/}
echo "マイグレーションを流しています: ${TASK_ID}（ログ: migrate/$CONTAINER/${TASK_ID}）"

# Aurora が一時停止していると、起こすのに十数秒かかる
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$TASK"

# イメージの取得に失敗したときなど、コンテナが起動しないまま止まると終了コードは null になる
read -r EXIT_CODE STOPPED_REASON < <(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" --output json |
  jq -r '.tasks[0] | "\(.containers[0].exitCode // "none") \(.stoppedReason // "")"')
if [[ "$EXIT_CODE" != "0" ]]; then
  echo "::error::マイグレーションが失敗しました（終了コード: ${EXIT_CODE}、理由: ${STOPPED_REASON}）。サービスは更新していません。CloudWatch Logs の migrate/$CONTAINER/$TASK_ID を見てください"
  summary "- マイグレーション: 失敗（終了コード ${EXIT_CODE}）。サービスは更新していない"
  exit 1
fi
echo "マイグレーションが終わりました"
summary "- マイグレーション: 成功（${MIGRATE_ARN##*/}）"

# ---- サービス ----

APP_FAMILY=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].taskDefinition' --output text | sed -E 's|.*/([^:]+):[0-9]+$|\1|')
APP_ARN=$(register "$APP_FAMILY")
echo "アプリのタスク定義: ${APP_ARN##*/}"

# 夜間の停止中（タスク数 0）なら、1 つ起動して載せる。止めたままでは、載せたものが動くかを確かめられない。
# 次の停止の時刻に、スケジュールがまた止める（modules/quiz-service/schedule.tf）
DESIRED=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].desiredCount' --output text)
if [[ "$DESIRED" == "0" ]]; then
  echo "サービスが止まっているため、1 つ起動して載せます"
  DESIRED=1
fi

DEPLOYMENT=$(aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$APP_ARN" \
  --desired-count "$DESIRED" \
  --query "service.deployments[?status=='PRIMARY'] | [0].id" --output text)
echo "サービスを入れ替えています: $DEPLOYMENT"

# サーキットブレーカーが前のリビジョンに戻すと、この入れ替えは FAILED になる。
# サービスとしては安定する（戻した先で動く）ため、aws ecs wait services-stable では失敗に気づけない
deadline=$((SECONDS + ROLLOUT_TIMEOUT_SECONDS))
while :; do
  # 戻したあとは、この入れ替えが一覧から消えることがある。そのときは STATE が空のまま残る
  STATE="" REASON=""
  read -r STATE REASON < <(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --output json |
    jq -r --arg id "$DEPLOYMENT" '.services[0].deployments[] | select(.id == $id) | "\(.rolloutState) \(.rolloutStateReason // "")"') || true
  case "${STATE:-GONE}" in
    COMPLETED)
      echo "入れ替えが終わりました"
      summary "- quiz-service: ${APP_ARN##*/}"
      break
      ;;
    IN_PROGRESS) ;;
    *)
      echo "::error::入れ替えに失敗しました（${STATE:-GONE}: ${REASON}）。前のリビジョンに戻っています"
      summary "- quiz-service: 失敗。前のリビジョンに戻った（${REASON}）"
      exit 1
      ;;
  esac
  if ((SECONDS >= deadline)); then
    echo "::error::入れ替えが ${ROLLOUT_TIMEOUT_SECONDS} 秒で終わりませんでした。ECS のサービスのイベントを見てください"
    exit 1
  fi
  sleep 15
done
