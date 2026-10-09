#!/bin/sh
# クイズのイベントを送るカスタムバス（ADR-0022）。AWS では Terraform が作る（modules/events）。
# LocalStack は再起動で中身が消えるため、起動のたびに作る。残っていれば何もしない
set -eu

BUS=quiz-app-local

if awslocal events describe-event-bus --name "$BUS" >/dev/null 2>&1; then
  echo "バスはすでにあります: $BUS"
else
  awslocal events create-event-bus --name "$BUS"
fi
