output "api_url" {
  value = "https://${aws_route53_record.api.fqdn}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.quiz_service.repository_url
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_name" {
  value = aws_ecs_service.app.name
}

# ---- デプロイのロール（modules/deploy-role）に渡す ----

output "ecr_repository_arn" {
  value = aws_ecr_repository.quiz_service.arn
}

output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "service_arn" {
  value = aws_ecs_service.app.id
}

output "task_definition_families" {
  value = {
    app     = aws_ecs_task_definition.app.family
    migrate = aws_ecs_task_definition.migrate.family
  }
}

output "task_role_arns" {
  value = [aws_iam_role.execution.arn, aws_iam_role.app.arn, aws_iam_role.migrate.arn]
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.quiz_service.name
}

# ---- ダッシュボード（modules/dashboard）に渡す ----

output "monitoring" {
  description = "メトリクスの次元、ロググループ、アラーム。ダッシュボードが読む"
  value = {
    api_id                = aws_apigatewayv2_api.this.id
    api_stage             = aws_apigatewayv2_stage.default.name
    cluster_name          = aws_ecs_cluster.this.name
    service_name          = aws_ecs_service.app.name
    log_group_name        = aws_cloudwatch_log_group.quiz_service.name
    access_log_group_name = aws_cloudwatch_log_group.api_access.name
    metric_namespace      = local.metric_namespace
    alarm_arns = [
      aws_cloudwatch_metric_alarm.server_errors.arn,
      aws_cloudwatch_metric_alarm.gateway_errors.arn,
      aws_cloudwatch_metric_alarm.outbox_oldest_unpublished.arn,
      aws_cloudwatch_metric_alarm.integrity_violations.arn,
    ]
  }
}
