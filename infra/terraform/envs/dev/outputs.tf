# Data API（aws rds-data）で SQL を流すときに使う。開発ガイドラインの「Aurora」を参照
output "database_cluster_arn" {
  value = local.app_database.cluster_arn
}

output "database_master_user_secret_arn" {
  value = local.app_database.master_user_secret_arn
}

output "quiz_service_url" {
  value = module.quiz_service.api_url
}

output "quiz_service_ecr_repository_url" {
  value = module.quiz_service.ecr_repository_url
}

output "ecs_cluster_name" {
  value = module.quiz_service.cluster_name
}

# GitHub の Environment dev の secret（AWS_ROLE_ARN）に入れる。開発ガイドラインの「デプロイ」を参照
output "deploy_role_arn" {
  value = module.deploy_role.role_arn
}

# クイズのイベントが届いたかは、アーカイブのイベント数で確かめる。開発ガイドラインの「イベント（EventBridge）」を参照
output "event_bus_name" {
  value = module.events.bus_name
}

output "events_archive_name" {
  value = module.events.archive_name
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

output "web_ssr_log_group_name" {
  value = module.web.ssr_log_group_name
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

output "demo_user_email" {
  value = module.auth.demo_user_email
}

# デモのアカウントのパスワード。見に来た人に公開する（README）
output "demo_user_password" {
  value     = module.auth.demo_user_password
  sensitive = true
}

# E2E テストの利用者の共通のパスワード（GitHub のリポジトリの secret E2E_USER_PASSWORD にも入れる）
output "e2e_user_password" {
  value     = module.auth.e2e_user_password
  sensitive = true
}

# 関数のコードを載せ替えるときと、送れなかったものを見るときに使う。開発ガイドラインの「通知（notification-service）」を参照
output "notification_function_name" {
  value = module.notification_service.function_name
}

output "notification_dlq_url" {
  value = module.notification_service.dlq_url
}

output "dashboard_url" {
  description = "CloudWatch のダッシュボード（DEV-109）"
  value       = module.dashboard.url
}
