#!/bin/sh
# 解説図の置き場所（ADR-0017）。AWS では Terraform が作る（modules/figures）。
# LocalStack は再起動で中身が消えるため、起動のたびに作る。残っていれば何もしない
set -eu

BUCKET=quiz-app-figures

if awslocal s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  echo "バケットはすでにあります: $BUCKET"
else
  awslocal s3 mb "s3://$BUCKET"
fi
