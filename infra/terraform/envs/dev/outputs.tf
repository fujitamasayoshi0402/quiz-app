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

# マイグレーションの単発タスク（aws ecs run-task）に渡す。開発ガイドラインの「ECS」を参照
output "ecs_cluster_name" {
  value = module.quiz_service.cluster_name
}

output "migrate_task_definition_arn" {
  value = module.quiz_service.migrate_task_definition_arn
}

output "migrate_network_configuration" {
  value = "awsvpcConfiguration={subnets=[${join(",", module.network.public_subnet_ids)}],securityGroups=[${module.network.security_group_ids.quiz_service}],assignPublicIp=ENABLED}"
}

output "web_url" {
  value = module.web.url
}

output "web_amplify_app_id" {
  value = module.web.app_id
}

# ベーシック認証。terraform output -raw web_basic_auth_password で取り出す
output "web_basic_auth_username" {
  value = module.web.basic_auth_username
}

output "web_basic_auth_password" {
  value     = module.web.basic_auth_password
  sensitive = true
}
