variable "notification_email" {
  description = "予算の通知を受け取るメールアドレス。公開リポジトリに載せないため、terraform.tfvars（Git の管理外）で渡す"
  type        = string
}
