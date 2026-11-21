#!/usr/bin/env bash
# つないでいる iPhone に、Release のビルドを入れ直す（DEV-246）。
# 無料の Apple ID の署名は 7 日で切れるので、切れたらこれを流す。初回の準備は docs/development-guidelines.md の「自分の端末には」。
#
#   pnpm --filter mobile device:ios                   # つないでいる iPhone が 1 台なら、それに入れる
#   IOS_DEVICE=<iPhone の ID> pnpm --filter mobile device:ios
#
# Claude Code の `!` からは流さない。サンドボックスの中では Xcode の DerivedData に書けない。
set -euo pipefail
cd "$(dirname "$0")/.."

# CocoaPods は、日本語などを含むパスで pod install が落ちる（incompatible character encodings）
if printf '%s' "$PWD" | LC_ALL=C grep -q '[^ -~]'; then
  echo "英数字だけのパスにある clone か worktree で流してください: $PWD" >&2
  exit 1
fi

device="${IOS_DEVICE:-}"
if [[ -z "$device" ]]; then
  list=$(mktemp)
  trap 'rm -f "$list"' EXIT
  xcrun devicectl list devices --json-output "$list" >/dev/null
  # Mac とペアリングした実機の iPhone（シミュレータを除く）
  found=$(jq -r '.result.devices[]
    | select(.hardwareProperties.reality == "physical" and .hardwareProperties.platform == "iOS"
             and .connectionProperties.pairingState == "paired")
    | "\(.hardwareProperties.udid)\t\(.deviceProperties.name)"' "$list")
  count=$(printf '%s' "$found" | grep -c . || true)
  if [[ "$count" -eq 0 ]]; then
    echo "iPhone が見つかりません。USB でつなぎ、ロックを解除してください" >&2
    exit 1
  fi
  if [[ "$count" -gt 1 ]]; then
    echo "iPhone が複数あります。IOS_DEVICE に ID を入れて流してください:" >&2
    printf '%s\n' "$found" >&2
    exit 1
  fi
  device=$(cut -f1 <<<"$found")
  echo "入れる先: $(cut -f2 <<<"$found")（${device}）"
fi

# Xcode で ios/Quiz.xcworkspace を開いて「推奨の設定に更新」を受けると、ENABLE_USER_SCRIPT_SANDBOXING が YES になる。
# JS を入れるスクリプトが宣言していないファイル（Debug の ip.txt など）に書けず、Operation not permitted で落ちるので戻す
project=ios/Quiz.xcodeproj/project.pbxproj
if [[ -f "$project" ]] && grep -q 'ENABLE_USER_SCRIPT_SANDBOXING = YES;' "$project"; then
  sed -i '' 's/ENABLE_USER_SCRIPT_SANDBOXING = YES;/ENABLE_USER_SCRIPT_SANDBOXING = NO;/' "$project"
  echo "ios/ の ENABLE_USER_SCRIPT_SANDBOXING を NO に戻しました"
fi

# Release は JS をアプリの中に入れるので、Metro も Mac も要らない
if ! pnpm exec expo run:ios --device "$device" --configuration Release --no-bundler; then
  cat >&2 <<'EOF'

入れたあとに開けないときは、iPhone の 設定 → 一般 → VPN とデバイス管理 で、自分の Apple ID を「信頼」してから開いてください。
ビルドが `No profiles ... were found` で止まったときは、Xcode で ios/Quiz.xcworkspace を開き、Signing & Capabilities で「Try Again」を押してから流し直してください。
EOF
  exit 1
fi
