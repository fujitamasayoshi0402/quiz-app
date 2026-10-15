variable "name" {
  description = "リソースの名前の頭（quiz-app-dev など）"
  type        = string
}

variable "package_path" {
  description = "関数を作るときにだけ読む zip。以後のコードの載せ替えは Terraform では行わない"
  type        = string
}

variable "feed_url" {
  description = "読む RSS（AWS の What's New）"
  type        = string
  default     = "https://aws.amazon.com/about-aws/whats-new/recent/feed/"
}

variable "model_id" {
  description = "Bedrock のモデル（推論プロファイル）。グローバルの推論プロファイルを使う（ADR-0031）"
  type        = string
  default     = "global.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "foundation_model_id" {
  description = "推論プロファイルが指す基盤モデル。権限の対象に使う"
  type        = string
  default     = "anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "max_drafts" {
  description = "1 回に作る下書きの上限"
  type        = number
  default     = 3
}

variable "schedule" {
  description = "起動する時刻（EventBridge Scheduler の式と、タイムゾーン）"
  type = object({
    expression = string
    timezone   = string
  })
  default = {
    expression = "cron(0 7 * * ? *)"
    timezone   = "Asia/Tokyo"
  }
}

variable "alarm_topic_arn" {
  description = "失敗を知らせる SNS のトピック（modules/alarms）"
  type        = string
}

variable "runbook_url" {
  description = "アラームの説明に載せる、Runbook の URL"
  type        = string
}

variable "memory_size" {
  type    = number
  default = 512
}

variable "log_retention_days" {
  type    = number
  default = 14
}
