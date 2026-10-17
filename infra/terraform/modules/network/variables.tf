variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

variable "cidr_block" {
  description = "VPC の CIDR。環境ごとに重ならないようにする（将来ピアリングするときに困らないように）"
  type        = string
}

variable "ipv6" {
  description = "VPC に Amazon の IPv6 の CIDR（/56）を付け、IPv6 の出口（Egress-only インターネットゲートウェイ）を置くか（DEV-183）"
  type        = bool
  default     = false
}

variable "azs" {
  description = "サブネットを置く AZ。Aurora が 2 つ以上を要求する。順番がサブネットの CIDR を決めるため、並べ替えない"
  type        = list(string)

  validation {
    condition     = length(var.azs) >= 2
    error_message = "Aurora の DB サブネットグループは 2 つ以上の AZ を要求する。"
  }
}
