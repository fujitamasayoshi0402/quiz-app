variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

variable "domain_name" {
  description = "図を配るドメイン名（例: figures.dev.example.com）。証明書と DNS のレコードを作る"
  type        = string
}

variable "hosted_zone_id" {
  description = "domain_name のレコードを置く Route 53 のホストゾーン"
  type        = string
}

variable "force_destroy" {
  description = "図が残っていてもバケットを消せるようにする。dev で環境ごと作り直すため"
  type        = bool
}
