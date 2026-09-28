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
