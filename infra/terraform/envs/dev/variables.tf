# 値は terraform.tfvars に書く（Git の管理外）。書き方は terraform.tfvars.example を参照

variable "domain_name" {
  description = "Route 53 に登録済みのドメイン。dev はそのサブドメインで公開する"
  type        = string
}

variable "quiz_service_image_tag" {
  description = "quiz-service のイメージのタグ（git のコミット）"
  type        = string
}
