variable "name" {
  description = "バスとアーカイブの名前（例: quiz-app-dev）"
  type        = string
}

variable "archive_retention_days" {
  description = "アーカイブに残す日数。quiz-service が Outbox の送れた行を消すまでの時間と揃える"
  type        = number
  default     = 7
}
