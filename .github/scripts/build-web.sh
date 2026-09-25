#!/usr/bin/env bash
# web を Amplify Hosting でビルドし、配信が切り替わるまで待つ（ADR-0012）。GitHub Actions（deploy-dev.yml）から呼ぶ。
#
#   build-web.sh <Amplify のアプリの ID> <ブランチ> <コミット>
#
# Amplify の自動ビルドは止めてある。quiz-service のデプロイの後に起動し、新しい画面が古い API を呼ばないようにする。
# コミットを指定して、ビルドの間に develop が進んでも、デプロイしたものと同じコミットの画面を配る。
set -euo pipefail

APP_ID=$1
BRANCH=$2
COMMIT=$3

# ビルドは 3 分ほど。キャッシュが効かないときや、前のビルドを待つときに延びる
TIMEOUT_SECONDS=1200

JOB=$(aws amplify start-job --app-id "$APP_ID" --branch-name "$BRANCH" \
  --job-type RELEASE --commit-id "$COMMIT" --commit-message "deploy ${COMMIT::12}" \
  --query 'jobSummary.jobId' --output text)
echo "Amplify のビルドを始めました: ジョブ $JOB"

deadline=$((SECONDS + TIMEOUT_SECONDS))
while :; do
  STATUS=$(aws amplify get-job --app-id "$APP_ID" --branch-name "$BRANCH" --job-id "$JOB" \
    --query 'job.summary.status' --output text)
  case "$STATUS" in
    SUCCEED)
      echo "ビルドと配信が終わりました"
      if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then echo "- web: Amplify のジョブ $JOB" >> "$GITHUB_STEP_SUMMARY"; fi
      break
      ;;
    FAILED | CANCELLED)
      # ビルドのログは Amplify のコンソールで見る。ログの URL は署名付きで、Public のログに出すと誰でも読める
      echo "::error::Amplify のビルドが $STATUS になりました（ジョブ ${JOB}）。Amplify のコンソールでログを見てください"
      exit 1
      ;;
  esac
  if ((SECONDS >= deadline)); then
    echo "::error::Amplify のビルドが ${TIMEOUT_SECONDS} 秒で終わりませんでした（ジョブ ${JOB}、状態 ${STATUS}）"
    exit 1
  fi
  sleep 20
done
