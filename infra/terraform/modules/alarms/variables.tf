variable "name" {
  description = "トピックの名前の頭（例: quiz-app-dev）"
  type        = string
}

variable "email" {
  description = "アラームを知らせるメールアドレス。公開リポジトリに載せないため、terraform.tfvars で渡す"
  type        = string
}
