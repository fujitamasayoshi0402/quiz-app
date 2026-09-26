# Data API（aws rds-data）で SQL を流すときに使う。開発ガイドラインの「Aurora」を参照
output "database_cluster_arn" {
  value = module.database.cluster_arn
}

output "database_master_user_secret_arn" {
  value = module.database.master_user_secret_arn
}

output "quiz_service_url" {
  value = module.quiz_service.api_url
}

output "quiz_service_ecr_repository_url" {
  value = module.quiz_service.ecr_repository_url
}

output "origin_header_name" {
  value = module.quiz_service.origin_header_name
}

output "origin_header_secret_arn" {
  value = module.quiz_service.origin_header_secret_arn
}

output "ecs_cluster_name" {
  value = module.quiz_service.cluster_name
}

# GitHub の Environment dev の secret（AWS_ROLE_ARN）に入れる。開発ガイドラインの「デプロイ」を参照
output "deploy_role_arn" {
  value = module.deploy_role.role_arn
}

output "figures_bucket_name" {
  value = module.figures.bucket_name
}

output "figures_url" {
  value = module.figures.base_url
}

output "web_url" {
  value = module.web.url
}

output "web_amplify_app_id" {
  value = module.web.app_id
}


output "auth_user_pool_id" {
  value = module.auth.user_pool_id
}

output "auth_issuer" {
  value = module.auth.issuer
}

output "auth_client_id" {
  value = module.auth.client_id
}

output "auth_managed_login_url" {
  description = "Managed Login の画面。手で確かめるときは、ここに /login?client_id=...&response_type=code&redirect_uri=... を付けて開く"
  value       = module.auth.managed_login_url
}

# スモークテストがトークンを取るのに使う（GitHub の Environment dev の secret にも入れる）
output "auth_client_secret" {
  value     = module.auth.client_secret
  sensitive = true
}

output "smoke_user_email" {
  value = module.auth.smoke_user_email
}

output "smoke_user_password" {
  value     = module.auth.smoke_user_password
  sensitive = true
}
