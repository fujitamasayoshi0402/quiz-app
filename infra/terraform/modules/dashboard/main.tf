# 運用で見るものを 1 画面にまとめる（DEV-109）。コンソールで直接変えない。変えたら、ここを書き換えて apply する。
# 見方は開発ガイドラインの「ダッシュボード」にある。
#
# 上から、アラーム、要求（API Gateway）、quiz-service（ECS）、Aurora、イベントと通知、ログの順に並べる。
# 要求の入口から DB、その先の非同期の処理へと、要求が流れる順にたどれるようにする。
#
# - 夜間（2:00〜8:00）はタスクを止めるため、ECS の線が途切れる。Aurora は使われないと止まり、ACU が 0 になる
# - ログのウィジェット（Logs Insights）は、開くたびに読んだ量だけ課金される。dev の量ではほぼ 0

data "aws_region" "current" {}

locals {
  region = data.aws_region.current.region
  qs     = var.quiz_service
  nt     = var.notification

  api_dimensions = ["ApiId", local.qs.api_id, "Stage", local.qs.api_stage]
  ecs_dimensions = ["ClusterName", local.qs.cluster_name, "ServiceName", local.qs.service_name]
  rds_dimensions = ["DBClusterIdentifier", var.database_cluster_identifier]
  rule_dimensions = [
    "EventBusName", local.nt.event_bus_name, "RuleName", local.nt.rule_name,
  ]

  # 折れ線のウィジェット。metrics は [名前空間, メトリクス, 次元..., { 集計など }] の並び
  timeseries = {
    view    = "timeSeries"
    region  = local.region
    period  = 300
    stacked = false
  }

  widgets = [
    {
      type   = "text"
      x      = 0
      y      = 0
      width  = 24
      height = 2
      properties = {
        markdown = "## ${var.name}\n夜間（2:00〜8:00、日本時間）は quiz-service のタスクを止める。Aurora は使われないと止まり、ACU が 0 になる。見方: [開発ガイドラインの「ダッシュボード」](${var.guide_url})"
      }
    },
    {
      type   = "alarm"
      x      = 0
      y      = 2
      width  = 24
      height = 3
      properties = {
        title  = "アラーム"
        alarms = concat(local.qs.alarm_arns, local.nt.alarm_arns)
      }
    },

    # ---- 要求（API Gateway） ----
    {
      type   = "metric"
      x      = 0
      y      = 5
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "要求の数"
        metrics = [
          concat(["AWS/ApiGateway", "Count"], local.api_dimensions, [{ stat = "Sum", label = "要求" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 5
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "応答の時間（ミリ秒）"
        metrics = [
          concat(["AWS/ApiGateway", "Latency"], local.api_dimensions, [{ stat = "p50", label = "p50" }]),
          concat(["AWS/ApiGateway", "Latency"], local.api_dimensions, [{ stat = "p95", label = "p95" }]),
          # API Gateway の中で使った時間を除いた、タスクの応答の時間。止まっている Aurora の復帰は、ここに表れる
          concat(["AWS/ApiGateway", "IntegrationLatency"], local.api_dimensions, [{ stat = "p95", label = "タスクの p95" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 5
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "エラー"
        metrics = [
          concat(["AWS/ApiGateway", "4xx"], local.api_dimensions, [{ stat = "Sum", label = "4xx" }]),
          # 夜間の停止中の 503 も入る
          concat(["AWS/ApiGateway", "5xx"], local.api_dimensions, [{ stat = "Sum", label = "5xx（API Gateway）" }]),
          [local.qs.metric_namespace, "ServerErrors", { stat = "Sum", label = "5xx（アプリ）" }],
          [local.qs.metric_namespace, "GatewayErrors", { stat = "Sum", label = "502 / 504" }],
        ]
      })
    },

    # ---- quiz-service（ECS） ----
    {
      type   = "metric"
      x      = 0
      y      = 11
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "動いているタスク"
        metrics = [
          concat(["AWS/ECS", "LiveTaskCount"], local.ecs_dimensions, [{ stat = "Maximum", label = "タスク" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 11
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "CPU とメモリ（%）"
        yAxis = { left = { min = 0, max = 100 } }
        metrics = [
          concat(["AWS/ECS", "CPUUtilization"], local.ecs_dimensions, [{ stat = "Maximum", label = "CPU" }]),
          concat(["AWS/ECS", "MemoryUtilization"], local.ecs_dimensions, [{ stat = "Maximum", label = "メモリ" }]),
        ]
      })
    },
    {
      # ルートの型ごとに、遅い順。要求の終わりの 1 行（RequestLogFilter）を数える
      type   = "log"
      x      = 16
      y      = 11
      width  = 8
      height = 6
      properties = {
        title  = "遅い API（p95、ミリ秒）"
        region = local.region
        view   = "table"
        query  = "SOURCE '${local.qs.log_group_name}' | filter ispresent(http.duration_ms) | stats count(*) as requests, pct(http.duration_ms, 95) as p95 by http.request.method, http.route | sort p95 desc | limit 10"
      }
    },

    # ---- Aurora ----
    {
      type   = "metric"
      x      = 0
      y      = 17
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "ACU（0 は一時停止）"
        metrics = [
          concat(["AWS/RDS", "ServerlessDatabaseCapacity"], local.rds_dimensions, [{ stat = "Maximum", label = "ACU" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 17
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "接続の数と CPU（%）"
        metrics = [
          # 接続が残っていると一時停止しない。止まらないときは、まずここを見る
          concat(["AWS/RDS", "DatabaseConnections"], local.rds_dimensions, [{ stat = "Maximum", label = "接続" }]),
          concat(["AWS/RDS", "CPUUtilization"], local.rds_dimensions, [{ stat = "Maximum", label = "CPU", yAxis = "right" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 17
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "Outbox の送れていない最も古いイベント（秒）"
        metrics = [
          [local.qs.metric_namespace, "OutboxOldestUnpublishedSeconds", { stat = "Maximum", label = "経過時間" }],
        ]
        # アラームの閾値（modules/quiz-service の alarms.tf）
        annotations = { horizontal = [{ label = "アラーム", value = 180 }] }
      })
    },

    # ---- イベントと通知 ----
    {
      type   = "metric"
      x      = 0
      y      = 23
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        # PutEvents の数は、バスごとには出ない。このアカウントで送るのは quiz-service だけ
        title = "EventBridge に送ったイベント"
        metrics = [
          ["AWS/Events", "PutEventsEntriesCount", { stat = "Sum", label = "送った" }],
          ["AWS/Events", "PutEventsFailedEntriesCount", { stat = "Sum", label = "失敗" }],
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 23
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "通知のルール"
        metrics = [
          concat(["AWS/Events", "MatchedEvents"], local.rule_dimensions, [{ stat = "Sum", label = "当てはまった" }]),
          concat(["AWS/Events", "Invocations"], local.rule_dimensions, [{ stat = "Sum", label = "Lambda に送った" }]),
          concat(["AWS/Events", "FailedInvocations"], local.rule_dimensions, [{ stat = "Sum", label = "送れなかった" }]),
        ]
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 23
      width  = 8
      height = 6
      properties = merge(local.timeseries, {
        title = "notification-service"
        metrics = [
          ["AWS/Lambda", "Invocations", "FunctionName", local.nt.function_name, { stat = "Sum", label = "実行" }],
          ["AWS/Lambda", "Errors", "FunctionName", local.nt.function_name, { stat = "Sum", label = "エラー" }],
          ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", local.nt.dlq_name, { stat = "Maximum", label = "DLQ" }],
        ]
      })
    },

    # ---- ログ ----
    {
      type   = "log"
      x      = 0
      y      = 29
      width  = 24
      height = 6
      properties = {
        title  = "quiz-service の警告と例外（新しい順）"
        region = local.region
        view   = "table"
        query  = "SOURCE '${local.qs.log_group_name}' | fields @timestamp, log.level, message, error.type, tenant.id, http.request.id | filter log.level in [\"WARN\", \"ERROR\"] | sort @timestamp desc | limit 20"
      }
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name
  dashboard_body = jsonencode({ widgets = local.widgets })
}
