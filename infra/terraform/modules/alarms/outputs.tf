output "topic_arn" {
  description = "アラームの送り先。各モジュールのアラームと、EventBridge のルールがここへ送る"
  value       = aws_sns_topic.this.arn
}
