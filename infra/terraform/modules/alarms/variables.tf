variable "name" {
  description = "トピックの名前の頭（例: quiz-app-dev）"
  type        = string
}

variable "label" {
  description = "メールの件名の頭に付ける、どこからの知らせか（例: dev）。「【dev】quiz-service が 5xx を返しました」のように出る"
  type        = string
}

variable "email" {
  description = "アラームを知らせるメールアドレス。公開リポジトリに載せないため、terraform.tfvars で渡す"
  type        = string
}

variable "allow_budgets" {
  description = "予算（AWS Budgets）からの送信を許すか。予算を置く account でだけ true にする"
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "日本語にする Lambda のログを残す日数"
  type        = number
  default     = 14
}
