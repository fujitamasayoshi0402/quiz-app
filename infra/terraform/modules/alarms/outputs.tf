output "topic_arn" {
  description = "アラームの送り先。各モジュールのアラームと、EventBridge のルールがここへ送る"
  value       = aws_sns_topic.this.arn
}

output "mail_topic_arn" {
  description = "日本語にしたメールを送るトピック。メールの購読はここにある"
  value       = aws_sns_topic.mail.arn
}
