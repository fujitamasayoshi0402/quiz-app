#!/usr/bin/env bash
# パックの図（figures/*.drawio）を SVG に書き出す（ADR-0034、DEV-186）。.svg より新しい .drawio だけを書き出す。
#
#   tools/content/export-svg.sh <パック>
#
# draw.io のデスクトップのアプリの CLI を使う（https://github.com/jgraph/drawio-desktop。Mac は brew install --cask drawio）。
# 書き出した SVG もパックに入れて commit する。流すときに CLI がなくてよいように
set -euo pipefail

PACK=${1:?パックのディレクトリを渡してください}
DRAWIO=${DRAWIO:-}
if [[ -z "$DRAWIO" ]]; then
  for candidate in drawio /Applications/draw.io.app/Contents/MacOS/draw.io; do
    if command -v "$candidate" > /dev/null; then DRAWIO=$candidate; break; fi
  done
fi
[[ -n "$DRAWIO" ]] || { echo "draw.io の CLI がありません（brew install --cask drawio）。場所は DRAWIO で渡せます" >&2; exit 1; }

shopt -s nullglob
for source in "$PACK"/figures/*.drawio; do
  svg="${source%.drawio}.svg"
  if [[ -f "$svg" && "$svg" -nt "$source" ]]; then continue; fi
  # 周りに余白を付ける。画面は図を白い面に出すので、端に寄った線が切れて見えないように
  "$DRAWIO" --export --format svg --theme light --embed-svg-fonts false --border 16 --output "$svg" "$source" > /dev/null
  echo "$svg"
done
