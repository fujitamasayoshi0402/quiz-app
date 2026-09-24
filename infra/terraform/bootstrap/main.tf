# tfstate を置く S3 バケット。dev / prod / bootstrap の state をキーで分けて同居させる（ADR-0011）。
#
# 名前の末尾は乱数。バケット名は全世界で一意なうえ、アカウント ID を公開リポジトリに出さないため。

locals {
  state_bucket = "quiz-app-tfstate-a0139cba"
}

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket

  # state を失うと、作ったリソースが Terraform の管理から外れる。誤って消せないようにする
  lifecycle {
    prevent_destroy = true
  }
}

# 壊した state を 1 つ前に戻せるようにする
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3。KMS のカスタマー管理キーは月額がかかるうえ、state にはパスワードを入れない方針のため要らない
# （DB のパスワードは Secrets Manager に置き、Terraform には持たせない）
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL を使わず、アクセスの制御をポリシーだけにする
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 暗号化されていない通信を拒否する
resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state.json

  # ポリシーを置く前にパブリックアクセスのブロックを効かせる
  depends_on = [aws_s3_bucket_public_access_block.state]
}

data "aws_iam_policy_document" "state" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

# 古い版は 90 日で消す。ロックファイル（*.tflock）も作っては消すため、放っておくと古い版がたまる
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # バージョニングが有効になってから設定する
  depends_on = [aws_s3_bucket_versioning.state]
}
