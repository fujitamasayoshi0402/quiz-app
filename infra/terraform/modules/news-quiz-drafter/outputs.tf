output "function_name" {
  description = "Lambda の関数名。コードを載せ替えるとき（aws lambda update-function-code）と、手で起動するときに使う"
  value       = aws_lambda_function.this.function_name
}

output "bucket_name" {
  description = "受け箱（inbox/）と、読んだ発表の記録（state/）のバケット"
  value       = aws_s3_bucket.this.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}
