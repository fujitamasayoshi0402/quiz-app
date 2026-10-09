output "state_bucket" {
  description = "tfstate を置くバケット。各環境の backend.tf に書く"
  value       = aws_s3_bucket.state.bucket
}
