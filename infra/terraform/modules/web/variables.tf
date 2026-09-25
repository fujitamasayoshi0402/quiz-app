variable "name" {
  description = "Amplify のアプリ名（例: quiz-app-dev-web）"
  type        = string
}

variable "repository_url" {
  type = string
}

variable "branch_name" {
  description = "ビルドして配る Git のブランチ"
  type        = string
}

variable "domain_name" {
  description = "Route 53 に登録済みのドメイン。web は <subdomain_prefix>.<domain_name> で公開する"
  type        = string
}

variable "subdomain_prefix" {
  type = string
}

variable "api_origin" {
  description = "proxy の中継先（quiz-service の ALB）。https:// から書く"
  type        = string
}

variable "origin_verify_secret" {
  description = "ALB が確かめる秘密のヘッダの値（modules/quiz-service）"
  type        = string
  sensitive   = true
}

variable "github_access_token" {
  description = "Amplify を GitHub につなぐトークン（admin:repo_hook）。アプリを作るときにだけ渡し、作ったら失効させる"
  type        = string
  sensitive   = true
  default     = null
}

variable "basic_auth_username" {
  type = string
}
