# 解説図（draw.io の原本と書き出した SVG、画像、PDF）を置き、利用者に配る（ADR-0017、ADR-0020）。
#
#   S3（非公開）── OAC ──→ CloudFront（署名付き URL だけを通す）──→ ブラウザ
#
# 誰に見せるかは quiz-service が決める。所属と図を確かめてから、期限の短い署名付き URL へ 302 で送る。
# CloudFront は署名を確かめるだけで、利用者もテナントも知らない。
#
# | キー                                  | 読み書きする者                             |
# | ------------------------------------- | ------------------------------------------ |
# | svg/{テナントの ID}/{図の ID}.svg        | quiz-service（読み書き）、CloudFront（読む） |
# | drawio/{テナントの ID}/{図の ID}.drawio  | quiz-service だけ                           |
# | img/{テナントの ID}/{図の ID}            | quiz-service（読み書き）、CloudFront（読む） |
# | pdf/{テナントの ID}/{図の ID}            | quiz-service（読み書き）、CloudFront（読む） |
# | incoming/{テナントの ID}/{図の ID}       | ブラウザ（署名付き URL で上げる）、quiz-service（検査する） |
#
# incoming/ は検査の前の置き場所。CloudFront には読ませず、残ったものは 1 日で消える
#
# 図は一度置いたら変えない。描き直した図は新しい ID で置くため、キャッシュを無効にする操作が要らない

resource "aws_s3_bucket" "this" {
  # バケット名は全世界で一意。末尾は Terraform が決める
  bucket_prefix = "${var.name}-figures-"
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL を使わない。読み書きの権限は、バケットのポリシーと IAM だけで決める
resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# SSE-S3。KMS のカスタマー管理キーは月額がかかり、OAC に復号の権限を渡す手間も増える
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "bucket" {
  # CloudFront に読ませるのは、配るもの（SVG、読み直した画像、PDF）だけ。
  # 原本は API を通して管理者にだけ返し、検査の前の画像（incoming/）は誰にも配らない
  statement {
    sid     = "CloudFrontReadsFigures"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.this.arn}/svg/*",
      "${aws_s3_bucket.this.arn}/img/*",
      "${aws_s3_bucket.this.arn}/pdf/*",
    ]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # このディストリビューションからの要求だけ。ほかの CloudFront から、このバケットを読ませない
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]

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

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket.json

  # ブロックパブリックアクセスが効いてからポリシーを付ける。順序が逆だと、公開のポリシーを一瞬でも許す余地がある
  depends_on = [aws_s3_bucket_public_access_block.this]
}

# ブラウザが画像を直接上げる（ADR-0020）。上げる URL は quiz-service が署名して出し、種類と大きさを署名に含める。
# CORS は、アプリの画面からの PUT だけを許す。読むのは CloudFront を通すので、GET は許さない
resource "aws_s3_bucket_cors_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = var.upload_allowed_origins
    allowed_headers = ["content-type"]
    max_age_seconds = 3000
  }
}

# 検査されずに残った画像（上げたまま完了しなかったもの）を消す
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "expire-incoming"
    status = "Enabled"

    filter {
      prefix = "incoming/"
    }

    expiration {
      days = 1
    }
  }
}
