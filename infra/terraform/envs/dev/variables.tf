# 値は terraform.tfvars に書く（Git の管理外）。書き方は terraform.tfvars.example を参照

variable "domain_name" {
  description = "Route 53 に登録済みのドメイン。dev はそのサブドメインで公開する"
  type        = string
}

variable "quiz_service_image_tag" {
  description = "quiz-service のイメージのタグ（git のコミット）。サービスを作るときにだけ使う。以降のデプロイは GitHub Actions が行う"
  type        = string
}

# ファイルには書かず、アプリを作るときだけ環境変数（TF_VAR_github_access_token）で渡す
variable "github_access_token" {
  description = "Amplify を GitHub につなぐトークン（admin:repo_hook）。作成後は失効させる"
  type        = string
  sensitive   = true
  default     = null
}
