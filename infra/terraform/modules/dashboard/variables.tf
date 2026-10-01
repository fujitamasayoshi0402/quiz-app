variable "name" {
  description = "ダッシュボードの名前（例: quiz-app-dev）"
  type        = string
}

variable "quiz_service" {
  description = "quiz-service のメトリクスの次元、ロググループ、アラーム（modules/quiz-service の monitoring）"
  type = object({
    api_id                = string
    api_stage             = string
    cluster_name          = string
    service_name          = string
    log_group_name        = string
    access_log_group_name = string
    metric_namespace      = string
    alarm_arns            = list(string)
  })
}

variable "notification" {
  description = "notification-service のメトリクスの次元とアラーム（modules/notification-service の monitoring）"
  type = object({
    function_name  = string
    event_bus_name = string
    rule_name      = string
    dlq_name       = string
    alarm_arns     = list(string)
  })
}

variable "database_cluster_identifier" {
  description = "Aurora のクラスタの名前"
  type        = string
}

variable "guide_url" {
  description = "見方を書いた文書（開発ガイドラインの「ダッシュボード」）の URL。ダッシュボードの先頭に置く"
  type        = string
}
