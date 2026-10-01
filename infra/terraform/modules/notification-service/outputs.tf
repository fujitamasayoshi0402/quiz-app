output "function_name" {
  description = "Lambda の関数名。コードを載せ替えるとき（aws lambda update-function-code）に使う"
  value       = aws_lambda_function.this.function_name
}

output "function_arn" {
  value = aws_lambda_function.this.arn
}

output "dlq_url" {
  description = "送れなかったイベントの置き場所（SQS）。中身を見るときに使う"
  value       = aws_sqs_queue.dlq.url
}

output "deliveries_table_name" {
  description = "重複を捨てる記録の DynamoDB の表"
  value       = aws_dynamodb_table.deliveries.name
}

output "monitoring" {
  description = "メトリクスの次元とアラーム。ダッシュボード（modules/dashboard）が読む"
  value = {
    function_name  = aws_lambda_function.this.function_name
    event_bus_name = var.event_bus_name
    rule_name      = aws_cloudwatch_event_rule.this.name
    dlq_name       = aws_sqs_queue.dlq.name
    alarm_arns     = [aws_cloudwatch_metric_alarm.dlq.arn]
  }
}
