output "cluster_endpoint" {
  description = "書き込み先のエンドポイント"
  value       = aws_rds_cluster.this.endpoint
}

output "port" {
  value = aws_rds_cluster.this.port
}

output "database_name" {
  value = aws_rds_cluster.this.database_name
}

output "cluster_arn" {
  description = "Data API（aws rds-data）に渡す"
  value       = aws_rds_cluster.this.arn
}

output "master_user_secret_arn" {
  description = "マスターユーザーの認証情報。Data API に渡す。アプリのタスクには渡さない"
  value       = aws_rds_cluster.this.master_user_secret[0].secret_arn
}

# ECS のタスクロールに rds-db:connect で与える。ロールごとに、どのタスクが使ってよいかを分ける
output "iam_db_user_arns" {
  description = "IAM 認証で接続するときの DB ユーザー。migrate はマイグレーションのタスクにだけ与える"
  value = {
    migrate = "arn:aws:rds-db:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:dbuser:${aws_rds_cluster.this.cluster_resource_id}/quiz"
    app     = "arn:aws:rds-db:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:dbuser:${aws_rds_cluster.this.cluster_resource_id}/quiz_app"
  }
}
