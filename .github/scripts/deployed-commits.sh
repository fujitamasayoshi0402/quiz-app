#!/usr/bin/env bash
# dev / prod でいま動いているもののコミットを調べる。deploy.yml が、何を載せるかを決めるのに使う（DEV-79）。手元からも同じように流せる。
#
#   deployed-commits.sh <クラスタ> <サービス> <Amplify のアプリの ID> <ブランチ> <通知の関数名>
#
# 次の 3 行を標準出力に出す（GITHUB_OUTPUT に書ける形）。調べられなかったものは空にする。呼び出し側は、空なら載せるものとして扱う。
#   backend=<quiz-service のコミット>
#   web=<web のコミット>
#   notification=<notification-service のコミット>
# 調べられなかった理由は、標準エラーに警告として出す
#
# 直前の push との差ではなく、動いているものとの差で決める。
# 続けてマージして待ちの実行が取り消されても、次の実行がその分もまとめて載せる。デプロイが失敗したときも、次の実行が載せ直す
set -euo pipefail

CLUSTER=$1
SERVICE=$2
APP_ID=$3
BRANCH=$4
FUNCTION=$5

CONTAINER=quiz-service

# 履歴にあるコミットだけを返す。Terraform が最初のタスク定義に入れたタグや、Amplify の手動のビルド（コミットが HEAD）のように、
# 辿れないものは空にする
commit_of() {
  if [[ "$1" =~ ^[0-9a-f]{7,40}$ ]] && git rev-parse --verify --quiet "$1^{commit}"; then
    return 0
  fi
  warn "$2（$1）は履歴にありません"
}

warn() {
  echo "::warning::$1。載せるものとして扱います" >&2
}

# quiz-service: サービスが指しているタスク定義のイメージのタグ。デプロイはコミットの SHA（先頭 12 桁）をタグにしている。
# 入れ替えに失敗して前のリビジョンに戻ったときは、戻った先を指す
backend_commit() {
  local task_definition image
  task_definition=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
    --query 'services[0].taskDefinition' --output text) || { warn "quiz-service のタスク定義を読めませんでした"; return 0; }
  image=$(aws ecs describe-task-definition --task-definition "$task_definition" \
    --query "taskDefinition.containerDefinitions[?name=='$CONTAINER'].image | [0]" --output text) || { warn "quiz-service のイメージを読めませんでした"; return 0; }
  commit_of "${image##*:}" "quiz-service のイメージのタグ"
}

# web: 最後に成功した Amplify のビルドのコミット。build-web.sh がコミットを指定して起動している。
# --output text にしない。text では --query がページ（20 件）ごとにかかり、ビルドが 20 件を超えると [0] がページの数だけ返る（DEV-85）。
# json なら、全ページをまとめてからかかる
web_commit() {
  local commit
  commit=$(aws amplify list-jobs --app-id "$APP_ID" --branch-name "$BRANCH" --max-items 50 \
    --query "jobSummaries[?status=='SUCCEED'] | [0].commitId" --output json) || { warn "Amplify のビルドの履歴を読めませんでした"; return 0; }
  commit_of "$(jq -r '. // empty' <<<"$commit")" "Amplify の最後のビルドのコミット"
}

# notification-service: 関数のタグ（DeployedCommit）。deploy-notification-service.sh が、載せて確かめたあとに書く。
# Terraform が作ったばかりの関数にはタグがない。空にして、載せるものとして扱う
notification_commit() {
  local arn commit
  arn=$(aws lambda get-function-configuration --function-name "$FUNCTION" \
    --query FunctionArn --output text) || { warn "notification-service の関数を読めませんでした"; return 0; }
  commit=$(aws lambda list-tags --resource "$arn" --query 'Tags.DeployedCommit' --output text) \
    || { warn "notification-service のタグを読めませんでした"; return 0; }
  if [[ "$commit" == "None" ]]; then
    warn "notification-service に載せたコミットの記録（タグ DeployedCommit）がありません"
    return 0
  fi
  commit_of "$commit" "notification-service のタグ"
}

echo "backend=$(backend_commit)"
echo "web=$(web_commit)"
echo "notification=$(notification_commit)"
