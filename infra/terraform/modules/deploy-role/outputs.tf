output "role_arn" {
  description = "GitHub の Environment の secret（AWS_ROLE_ARN）に入れる"
  value       = aws_iam_role.this.arn
}
