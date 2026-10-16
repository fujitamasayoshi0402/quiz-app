#!/usr/bin/env bash
# capture/output/videos/ の動画を GIF にして、docs/images/ に置く（DEV-180）。先に `pnpm --filter e2e capture` で撮る。
#
#   tests/e2e/capture/make-gifs.sh
#
# README で読みやすく、重くしすぎないように、幅とコマ数と色数を絞る。1 本 数 MB に収める。
# 色は動画ごとに選び直す（palettegen）。決まった色の組だと、画面の灰色や青がまだらになる
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
VIDEOS="$HERE/output/videos"
IMAGES="$HERE/../../../docs/images"

command -v ffmpeg > /dev/null || { echo "ffmpeg がありません（brew install ffmpeg）" >&2; exit 1; }

# <動画の名前> <GIF の幅> <切り出す範囲（ffmpeg の crop。全体なら iw:ih:0:0）> <先頭から飛ばす秒>
gif() {
  local name=$1 width=$2 crop=$3 skip=$4 src="$VIDEOS/$1.webm" out="$IMAGES/$1.gif"
  [[ -f "$src" ]] || { echo "$src がありません。先に pnpm --filter e2e capture を流してください" >&2; exit 1; }
  local palette="palettegen=max_colors=96:stats_mode=diff"
  local dither="paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle"
  ffmpeg -loglevel error -y -ss "$skip" -i "$src" \
    -vf "crop=${crop},fps=10,scale=${width}:-1:flags=lanczos,split[a][b];[a]${palette}[p];[b][p]${dither}" "$out"
  echo "$out ($(du -h "$out" | cut -f1))"
}

# スマホの幅（390px）で撮ったもの。そのまま縮める
gif demo-play 320 iw:ih:0:0 0
# パソコンの幅（1280px）で撮ったもの。画面の中身は中央の 640px にあるので、左右の余白を落として文字を読めるようにする。
# 最初の 2 秒は、読み込みの途中で何も映っていない
gif admin-ai-draft 600 720:ih:280:0 2
