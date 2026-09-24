variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

variable "engine_version" {
  description = "Aurora PostgreSQL のバージョン。メジャーはローカル（docker-compose の postgres:16）と揃える"
  type        = string
}

variable "subnet_ids" {
  description = "Aurora を置くサブネット。インターネットへの経路がないプライベートサブネットを渡す"
  type        = list(string)
}

variable "security_group_id" {
  description = "Aurora に付ける SecurityGroup。受信は quiz-service からだけ（modules/network）"
  type        = string
}

variable "max_capacity" {
  description = "Serverless v2 の上限（ACU）。費用の上限にもなる"
  type        = number
}

variable "seconds_until_auto_pause" {
  description = "接続がなくなってから一時停止するまでの秒数（300〜86400）"
  type        = number
}

variable "backup_retention_days" {
  description = "自動バックアップの保持日数"
  type        = number
}

variable "deletion_protection" {
  description = "削除保護。有効にすると、削除時に最終スナップショットも取る"
  type        = bool
}

variable "apply_immediately" {
  description = "設定の変更を、メンテナンスウィンドウを待たずに反映する"
  type        = bool
}
