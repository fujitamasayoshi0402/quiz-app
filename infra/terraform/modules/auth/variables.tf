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

variable "mobile_app_url_prefixes" {
  description = <<-EOT
    モバイルアプリ（apps/mobile）の戻り先の接頭辞（例: quizapp://、exp://127.0.0.1:8081/--/）。空ならアプリのクライアントを作らない（ADR-0032）。
    コールバックは <接頭辞>auth/callback、ログアウトのあとは <接頭辞>auth/logout に戻る
  EOT
  type        = list(string)
  default     = []
}

variable "smoke_user_email" {
  description = "スモークテストが使う利用者のメールアドレス。null なら作らない"
  type        = string
  default     = null
}

variable "demo_user_email" {
  description = "見に来た人が試すための、共有のデモのアカウントのメールアドレス。null なら作らない。パスワードは公開する前提"
  type        = string
  default     = null
}

variable "e2e_user_emails" {
  description = "E2E テスト（tests/e2e）が使う利用者のメールアドレス。空なら作らない。パスワードは全員で共通"
  type        = set(string)
  default     = []
}

variable "load_user_emails" {
  description = "負荷試験（tests/load）が使う利用者のメールアドレス。空なら作らない。パスワードは全員で共通"
  type        = set(string)
  default     = []
}

variable "deletion_protection" {
  description = "User Pool を消せないようにする。消すと、利用者とパスキーがすべて失われる"
  type        = bool
}
