variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

variable "app_origins" {
  description = <<-EOT
    ログインのあとに戻ってくる web のオリジン（例: https://dev.example.com、http://localhost:3000）。
    コールバックは <オリジン>/auth/callback、ログアウトのあとは <オリジン>/ に戻る
  EOT
  type        = list(string)
}

variable "smoke_user_email" {
  description = "スモークテストが使う利用者のメールアドレス。null なら作らない"
  type        = string
  default     = null
}

variable "e2e_user_emails" {
  description = "E2E テスト（tests/e2e）が使う利用者のメールアドレス。空なら作らない。パスワードは全員で共通"
  type        = set(string)
  default     = []
}

variable "deletion_protection" {
  description = "User Pool を消せないようにする。消すと、利用者とパスキーがすべて失われる"
  type        = bool
}
