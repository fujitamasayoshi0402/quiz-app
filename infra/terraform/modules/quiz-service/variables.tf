variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

# ---- ネットワーク（modules/network） ----

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  description = "ALB とタスクを置く。タスクはパブリック IP から AWS の API へ出る（ADR-0013）"
  type        = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "service_security_group_id" {
  description = "quiz-service とマイグレーションのタスクに付ける"
  type        = string
}

# ---- データベース（modules/database） ----

variable "db_endpoint" {
  type = string
}

variable "db_port" {
  type = number
}

variable "db_name" {
  type = string
}

variable "iam_db_user_arns" {
  description = "rds-db:connect の対象。app はアプリのタスク、migrate はマイグレーションのタスクにだけ与える"
  type = object({
    app     = string
    migrate = string
  })
}

# ---- タスク ----

variable "image_tag" {
  description = "動かすイメージのタグ（git のコミット）。タグは上書きできないため、同じタグは同じ中身を指す"
  type        = string
}

variable "cpu" {
  description = "タスクの vCPU（1024 = 1 vCPU）"
  type        = number
}

variable "memory" {
  description = "タスクのメモリ（MiB）。ヒープはその 75%（Dockerfile の MaxRAMPercentage）"
  type        = number
}

variable "desired_count" {
  type = number
}

variable "spring_profiles" {
  description = "アプリの Spring プロファイル。マイグレーションのタスクには migrate を足して渡す"
  type        = list(string)
}

variable "log_retention_days" {
  type = number
}

variable "force_delete_images" {
  description = "イメージが残っていてもリポジトリを消せるようにする。dev で環境ごと作り直すため"
  type        = bool
}
