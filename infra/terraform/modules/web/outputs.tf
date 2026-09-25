output "app_id" {
  description = "ビルドの起動（aws amplify start-job）に使う"
  value       = aws_amplify_app.this.id
}

output "branch_name" {
  value = aws_amplify_branch.this.branch_name
}

output "branch_arn" {
  description = "デプロイのロール（modules/deploy-role）が、このブランチのビルドだけを起動できるようにする"
  value       = aws_amplify_branch.this.arn
}

output "url" {
  value = "https://${var.subdomain_prefix}.${var.domain_name}"
}

output "basic_auth_username" {
  value = var.basic_auth_username
}

output "basic_auth_password" {
  value     = random_password.basic_auth.result
  sensitive = true
}
