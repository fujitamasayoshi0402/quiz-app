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

variable "alarm_email" {
  description = "アラームを知らせるメールアドレス（modules/alarms）。公開リポジトリに載せない"
  type        = string
}

variable "database_restore" {
  description = <<-EOT
    Aurora をバックアップから戻すとき（Runbook「Aurora のデータを戻す」）。null なら、戻したクラスタを置かない。
    restore_to_time: 戻す時刻（RFC 3339、UTC）。null なら戻せる最も新しい時刻。use_for_app: true なら、アプリを戻したクラスタにつなぐ
  EOT
  type = object({
    restore_to_time = optional(string)
    use_for_app     = optional(bool, false)
  })
  default = null
}

variable "api_throttling" {
  description = <<-EOT
    API 全体の流量の上限。ふだんはモジュールの既定（20 件/秒、バースト 60）のまま。
    負荷試験の間だけ -var で上げ、終わったら -var を付けずに apply して戻す（開発ガイドラインの「負荷試験」）
  EOT
  type = object({
    rate_limit  = number
    burst_limit = number
  })
  default = {
    rate_limit  = 20
    burst_limit = 60
  }
}
