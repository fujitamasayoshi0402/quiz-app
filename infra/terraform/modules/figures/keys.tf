# 署名付き URL の鍵の組（ADR-0017）。
#
# 公開鍵を CloudFront のキーグループに登録し、秘密鍵を quiz-service に渡す。
# CloudFront は、キーグループの鍵で署名された URL だけを通す。
#
# **秘密鍵は Terraform の state にも入る。** state のバケットは非公開で暗号化しており、
# ほかの秘密（Cookie の暗号鍵など）と同じ扱いになる。
#
# 入れ替えるときは、新しい公開鍵をキーグループに足し、アプリを新しい鍵に替えてから（デプロイ）、古い公開鍵を外す。
# 発行済みの URL は最長 10 分で切れる

resource "tls_private_key" "signing" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_cloudfront_public_key" "signing" {
  # 鍵を入れ替えるとき、新旧が並ぶ。名前を固定すると重なる
  name_prefix = "${var.name}-figures-"
  encoded_key = tls_private_key.signing.public_key_pem
  comment     = "Signs figure URLs issued by quiz-service"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudfront_key_group" "this" {
  name  = "${var.name}-figures"
  items = [aws_cloudfront_public_key.signing.id]
}

# Secrets Manager ではなく Parameter Store に置く。自動のローテーションは使わず、標準のパラメータは料金がかからない。
# AWS 管理のキー（aws/ssm）で暗号化される。ECS が読むときに、実行ロールへ kms:Decrypt を与えなくてよい
resource "aws_ssm_parameter" "signing_key" {
  name        = "/${var.name}/figures/cloudfront-private-key"
  description = "Private key that signs CloudFront URLs for figures (PKCS#8 PEM)"
  type        = "SecureString"
  value       = tls_private_key.signing.private_key_pem_pkcs8
}
