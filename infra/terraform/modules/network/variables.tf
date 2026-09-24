variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

variable "cidr_block" {
  description = "VPC の CIDR。環境ごとに重ならないようにする（将来ピアリングするときに困らないように）"
  type        = string
}

variable "azs" {
  description = "サブネットを置く AZ。ALB と Aurora が 2 つ以上を要求する。順番がサブネットの CIDR を決めるため、並べ替えない"
  type        = list(string)

  validation {
    condition     = length(var.azs) >= 2
    error_message = "ALB と Aurora の DB サブネットグループは 2 つ以上の AZ を要求する。"
  }
}
