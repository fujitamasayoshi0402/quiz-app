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

variable "api_domain_name" {
  description = "API を公開するドメイン名（例: api.dev.example.com）。証明書と DNS のレコードを作る"
  type        = string
}

variable "hosted_zone_id" {
  description = "api_domain_name のレコードを置く Route 53 のホストゾーン"
  type        = string
}

# ---- 認証（modules/auth） ----

variable "auth_issuer" {
  description = "アクセストークンの発行者。JWT の iss と比べ、署名の鍵（JWKS）と userinfo の場所もここから引く"
  type        = string
}

variable "auth_client_id" {
  description = "トークンを受け取る web のクライアント。ほかのクライアントに発行されたトークンは受け付けない"
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
  description = "Terraform がタスク定義を登録するときのイメージのタグ（git のコミット）。サービスを作るときにだけ動く。以降はデプロイが差し替える"
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
  description = "動かすタスクの数。サービスを作るときと、夜間の停止から戻すときに使う"
  type        = number
}

variable "nightly_stop" {
  description = <<-EOT
    夜間にサービスを止める時間帯。止める時刻（stop）と戻す時刻（start）を、EventBridge Scheduler の cron 式で渡す。
    null なら止めない
  EOT
  type = object({
    stop     = string
    start    = string
    timezone = string
  })
  default = null
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
