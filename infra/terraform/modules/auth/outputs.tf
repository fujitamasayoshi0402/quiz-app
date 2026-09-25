output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "issuer" {
  description = "JWT の発行者。web は OIDC の設定（/.well-known/openid-configuration）をここから読み、バックエンドは JWT の iss と比べる"
  value       = "https://${aws_cognito_user_pool.this.endpoint}"
}

output "managed_login_url" {
  value = "https://${local.domain_fqdn}"
}

output "client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "client_secret" {
  value     = aws_cognito_user_pool_client.web.client_secret
  sensitive = true
}

output "smoke_user_email" {
  value = var.smoke_user_email
}

output "smoke_user_password" {
  value     = one(random_password.smoke[*].result)
  sensitive = true
}
