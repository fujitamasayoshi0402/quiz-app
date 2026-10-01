output "bus_name" {
  description = "カスタムバスの名前。quiz-service がこのバスへ送る"
  value       = aws_cloudwatch_event_bus.this.name
}

output "bus_arn" {
  description = "カスタムバスの ARN。送る権限（events:PutEvents）を、このバスだけに絞る"
  value       = aws_cloudwatch_event_bus.this.arn
}

output "archive_name" {
  description = "アーカイブの名前。イベント数で、届いたかどうかを確かめる"
  value       = aws_cloudwatch_event_archive.this.name
}
