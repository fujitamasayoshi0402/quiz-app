# 値は terraform.tfvars に書く（Git の管理外）。書き方は terraform.tfvars.example を参照

variable "alb_ingress_cidrs" {
  description = "ALB への受信を許す CIDR。HTTPS（DEV-47）が入るまでは手元の IP だけにする"
  type        = list(string)
  default     = []
}

variable "quiz_service_image_tag" {
  description = "quiz-service のイメージのタグ（git のコミット）"
  type        = string
}
