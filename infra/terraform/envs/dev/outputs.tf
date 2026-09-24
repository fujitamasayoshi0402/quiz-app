# Data API（aws rds-data）で SQL を流すときに使う。開発ガイドラインの「Aurora」を参照
output "database_cluster_arn" {
  value = module.database.cluster_arn
}

output "database_master_user_secret_arn" {
  value = module.database.master_user_secret_arn
}
