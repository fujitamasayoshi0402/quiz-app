variable "name" {
  description = "リソースの名前の頭（例: quiz-app-dev）"
  type        = string
}

variable "event_bus_name" {
  description = "クイズのイベントが流れるカスタムバス（modules/events）"
  type        = string
}

variable "webhook_parameter_prefix" {
  description = "テナントの Slack の Webhook の URL を置く SSM のパラメータの頭。quiz-service と同じ値（例: /quiz-app/dev）"
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9/_-]+[^/]$", var.webhook_parameter_prefix))
    error_message = "/ で始まり、/ で終わらないパスにしてください（例: /quiz-app/dev）"
  }
}

variable "web_base_url" {
  description = "通知に載せる管理画面へのリンクのオリジン（例: https://dev.example.com）"
  type        = string
}

variable "package_path" {
  description = "Lambda に載せる zip（./gradlew :services:notification-service:buildZip で作る）。関数を作るときにだけ使う"
  type        = string
}

variable "alarm_topic_arn" {
  description = "送れなかったイベントが DLQ に入ったときに知らせる先（modules/alarms の SNS のトピック）"
  type        = string
}

variable "memory_size" {
  description = "Lambda のメモリ（MB）。CPU もこれに比例して割り当てられ、JVM の起動の速さに効く"
  type        = number
  default     = 512
}

variable "log_retention_days" {
  description = "Lambda のログを残す日数"
  type        = number
  default     = 14
}

variable "runbook_url" {
  description = "Runbook（docs/runbook.md）の URL。アラームの説明に、鳴ったときの手順の節へのリンクとして載せる"
  type        = string
}
