variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

# ---- ネットワーク（modules/network） ----

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  description = "タスクを置く。タスクはパブリック IP から AWS の API へ出る（ADR-0013）"
  type        = list(string)
}

variable "service_security_group_id" {
  description = "quiz-service とマイグレーションのタスクに付ける"
  type        = string
}

variable "vpc_link_subnet_ids" {
  description = "API Gateway の VPC リンクの ENI を置く。外へ出る必要がないので、プライベートサブネットでよい"
  type        = list(string)
}

variable "vpc_link_security_group_id" {
  description = "VPC リンクの ENI に付ける。quiz-service のタスクへだけ出られる"
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
  description = "アクセストークンの発行者。JWT の iss と比べ、署名の鍵（JWKS）と GetUser のエンドポイントもここから引く"
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

# ---- 解説図（modules/figures） ----

variable "figures" {
  description = <<-EOT
    解説図の置き場所と、署名付き URL の作り方（ADR-0017）。アプリのタスクにだけ渡す。
    base_url と key_pair_id は CloudFront、private_key_parameter_arn は署名の秘密鍵を置いた SSM のパラメータ
  EOT
  type = object({
    bucket_name               = string
    bucket_arn                = string
    base_url                  = string
    key_pair_id               = string
    private_key_parameter_arn = string
  })
}

# ---- 通知（ADR-0022） ----

variable "slack_webhook_parameter_prefix" {
  description = <<-EOT
    テナントの Slack の Webhook の URL を置く、SSM のパラメータの名前の頭（例: /quiz-app/dev）。
    この下の tenants/{テナントの ID}/slack-webhook-url に置く。アプリは書き込みと削除だけができ、読めない
  EOT
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9/_.-]+[^/]$", var.slack_webhook_parameter_prefix))
    error_message = "/ で始まり、/ で終わらない名前にしてください"
  }
}

variable "event_bus" {
  description = "クイズのイベントを送る EventBridge のカスタムバス（ADR-0022）。アプリのタスクにだけ渡し、送る権限もこのバスだけに絞る"
  type = object({
    name = string
    arn  = string
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

variable "throttling" {
  description = <<-EOT
    API 全体の流量の上限（件/秒と、バースト）。叩かれ続けても、費用に上限を付ける。
    20 件/秒で 1 か月続いても約 5,200 万回（約 67 ドル）で、予算の通知が先に届く。
    **API 全体に 1 つ。** 1 人がこれを使い切ると全員が 429 になるため、quiz-service が利用者ごとの上限
    （5 件/秒、バースト 30）を掛ける（DEV-125、RateLimitProperties）。利用者ごとの上限より十分大きくする。
    同じだと、1 人で全員を止められる。アクセストークンのない要求は利用者ごとには数えられず、ここで受ける
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

variable "alarm_topic_arn" {
  description = "アラームの送り先（modules/alarms の SNS のトピック）"
  type        = string
}

variable "runbook_url" {
  description = "Runbook（docs/runbook.md）の URL。アラームの説明に、鳴ったときの手順の節へのリンクとして載せる"
  type        = string
}

variable "tracing_sampling_probability" {
  description = "トレースを記録する要求の割合（0〜1。ADR-0026）"
  type        = number

  validation {
    condition     = var.tracing_sampling_probability >= 0 && var.tracing_sampling_probability <= 1
    error_message = "0 から 1 の間で指定してください"
  }
}
