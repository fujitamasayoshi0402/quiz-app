# ---- quiz-service（modules/quiz-service）に渡す ----

output "bucket_name" {
  value = aws_s3_bucket.this.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}

output "base_url" {
  description = "署名付き URL の土台。この下に S3 のキー（svg/...）を続ける"
  value       = "https://${aws_route53_record.a.fqdn}"
}

output "key_pair_id" {
  description = "署名に使った鍵の ID。署名付き URL の Key-Pair-Id に入る"
  value       = aws_cloudfront_public_key.signing.id
}

output "private_key_parameter_arn" {
  description = "署名の秘密鍵（PKCS#8 の PEM）。アプリのタスクに ECS の secrets で渡す"
  value       = aws_ssm_parameter.signing_key.arn
}

output "distribution_id" {
  value = aws_cloudfront_distribution.this.id
}
