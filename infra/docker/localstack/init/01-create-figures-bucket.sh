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

# ブラウザが画像を直接上げる（ADR-0020）。AWS と同じく、画面のオリジンからの PUT だけを許す
awslocal s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration "{
  \"CORSRules\": [{
    \"AllowedMethods\": [\"PUT\"],
    \"AllowedOrigins\": [\"${FIGURES_UPLOAD_ORIGIN:-http://localhost:3000}\"],
    \"AllowedHeaders\": [\"content-type\"],
    \"MaxAgeSeconds\": 3000
  }]
}"
