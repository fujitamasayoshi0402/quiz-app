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
  description = "proxy の中継先（quiz-service の API Gateway）。https:// から書く"
  type        = string
}

variable "content_security_policy_origins" {
  description = "画面の CSP で許す、環境ごとに違う送り先（DEV-123）。images は解説図の CDN、connect は S3 への直接のアップロード"
  type = object({
    images  = list(string)
    connect = list(string)
  })
}

variable "auth" {
  description = "ログイン（modules/auth）。web のサーバーが OIDC のクライアントとして使う"
  type = object({
    issuer        = string
    client_id     = string
    client_secret = string
  })
  sensitive = true
}

variable "github_access_token" {
  description = "Amplify を GitHub につなぐトークン（admin:repo_hook）。アプリを作るときにだけ渡し、作ったら失効させる"
  type        = string
  sensitive   = true
  default     = null
}


variable "log_retention_days" {
  description = "SSR のログを残す日数"
  type        = number
}
