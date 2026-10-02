# quiz-service のアラーム（DEV-108）。送り先は環境で共通のトピック（modules/alarms）。
# 一覧と、鳴ったときに見るものは、開発ガイドラインの「アラーム」にある。
#
# 数えるものの多くは、アプリが JSON で出すログ（DEV-107）から、メトリクスフィルタで作る。
# **ログの項目の名前を変えると、ここが黙って数えなくなる。** 名前は RequestLogFilter、OutboxRelay、IntegrityCheck にある。
#
# **誤報を出さない。** 夜間の停止（schedule.tf）、デプロイの入れ替え、止まっている Aurora の復帰は、ふつうに起きる。
# - API Gateway の 5xx の率は使わない。止まっている間の 503 が入る。アプリの 5xx と、API Gateway が送り先から応答を得られなかったもの（502 / 504）を分けて数える
# - 復帰が遅いときの 504 は、1 回なら鳴らさない
# - タスクが止まったうち、ECS が自分で止めたもの（デプロイ、夜間の停止）は知らせない
#
# データが無い時間（使われていない、夜間）は、異常とみなさない（treat_missing_data = notBreaching）

locals {
  metric_namespace = "${var.name}/quiz-service"

  # 鳴ったときの手順（docs/runbook.md）の節。見出しを変えたら、ここも直す
  runbook = {
    server_errors  = "${var.runbook_url}#${urlencode("アプリが-5xx-を返した")}"
    gateway_errors = "${var.runbook_url}#${urlencode("api-gateway-が-502--504-を返した")}"
    outbox         = "${var.runbook_url}#${urlencode("イベントを送れていないoutbox")}"
    integrity      = "${var.runbook_url}#${urlencode("データの整合性が崩れている")}"
    task_stopped   = "${var.runbook_url}#${urlencode("タスクの停止のメール")}"
  }
}

# ---- アプリが 5xx を返した ----
# アプリの不具合。1 回でも知らせる。扱っていない例外も、要求の終わりの 1 行に 500 として出る

resource "aws_cloudwatch_log_metric_filter" "server_errors" {
  name           = "${var.name}-quiz-service-server-errors"
  log_group_name = aws_cloudwatch_log_group.quiz_service.name
  pattern        = "{ $.http.response.status_code >= 500 }"

  metric_transformation {
    namespace = local.metric_namespace
    name      = "ServerErrors"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "server_errors" {
  alarm_name        = "${var.name}-quiz-service-server-errors"
  alarm_description = "quiz-service が 5xx を返しました。Logs Insights で http.response.status_code >= 500 の行から http.request.id を引き、その要求のログを追う。手順: ${local.runbook.server_errors}"

  namespace   = local.metric_namespace
  metric_name = aws_cloudwatch_log_metric_filter.server_errors.metric_transformation[0].name
  statistic   = "Sum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}

# ---- API Gateway が送り先から応答を得られなかった ----
# 502（不正な応答）と 504（30 秒を超えた）。タスクが固まった、DB の接続を待ち続けている、など。
# 503（送り先のタスクがない）は数えない。夜間の停止中に必ず出る。昼に止まったことは、下のタスクの停止で知らせる。
# 止まっている Aurora の復帰が 30 秒を超えると 504 が 1 回出て、画面が再試行する。5 分で 3 回からにして、これでは鳴らさない

resource "aws_cloudwatch_log_metric_filter" "gateway_errors" {
  name           = "${var.name}-quiz-service-gateway-errors"
  log_group_name = aws_cloudwatch_log_group.api_access.name
  pattern        = "{ ($.status = \"502\") || ($.status = \"504\") }"

  metric_transformation {
    namespace = local.metric_namespace
    name      = "GatewayErrors"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "gateway_errors" {
  alarm_name        = "${var.name}-quiz-service-gateway-errors"
  alarm_description = "API Gateway が quiz-service から応答を得られません（502 / 504）。アクセスログの requestId と、アプリのログの http.request.id を突き合わせる。手順: ${local.runbook.gateway_errors}"

  namespace   = local.metric_namespace
  metric_name = aws_cloudwatch_log_metric_filter.gateway_errors.metric_transformation[0].name
  statistic   = "Sum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 3
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}

# ---- Outbox に送れていないイベントが残っている ----
# 拾い直し（OutboxRelay）が、送れていない最も古い行の経過時間を出す。コミットの直後の送信と、拾い直し 2 回で送れていなければ鳴らす。
# 拾い直しは、利用者が DB を使ってから 5 分しか動かない（ADR-0022）。閾値はそれより短くする。長いと、測れないうちに止まる

resource "aws_cloudwatch_log_metric_filter" "outbox_oldest_unpublished" {
  name           = "${var.name}-quiz-service-outbox-oldest-unpublished"
  log_group_name = aws_cloudwatch_log_group.quiz_service.name
  pattern        = "{ $.outbox.oldest_unpublished_seconds >= 0 }"

  metric_transformation {
    namespace = local.metric_namespace
    name      = "OutboxOldestUnpublishedSeconds"
    value     = "$.outbox.oldest_unpublished_seconds"
    unit      = "Seconds"
  }
}

resource "aws_cloudwatch_metric_alarm" "outbox_oldest_unpublished" {
  alarm_name        = "${var.name}-quiz-service-outbox-stuck"
  alarm_description = "Outbox に 3 分以上送れていないイベントがあります。EventBridge に送れていない。ログの「イベントを送れませんでした」で理由を見る。手順: ${local.runbook.outbox}"

  namespace   = local.metric_namespace
  metric_name = aws_cloudwatch_log_metric_filter.outbox_oldest_unpublished.metric_transformation[0].name
  statistic   = "Maximum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 180
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}

# ---- データの整合性が崩れている ----
# DB の制約で表しきれない決まり（公開中のクイズの選択肢と正解と解説、解説が指す図、削除の連鎖）を、
# 利用者が DB を使っている間に 1 日 1 回確かめる（IntegrityCheck、DEV-115）。崩れていたものの件数を出す。
# 1 件でも鳴らす。シードや手で流した SQL で崩れたもので、時間がたっても直らない

resource "aws_cloudwatch_log_metric_filter" "integrity_violations" {
  name           = "${var.name}-quiz-service-integrity-violations"
  log_group_name = aws_cloudwatch_log_group.quiz_service.name
  pattern        = "{ $.integrity.violations >= 0 }"

  metric_transformation {
    namespace = local.metric_namespace
    name      = "IntegrityViolations"
    value     = "$.integrity.violations"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "integrity_violations" {
  alarm_name        = "${var.name}-quiz-service-integrity-violations"
  alarm_description = "データの整合性が崩れています。ログの「データの整合性が崩れています」で、決まり（integrity.rule）と tenant.id、対象の ID（integrity.subject_id）を見る。手順: ${local.runbook.integrity}"

  namespace   = local.metric_namespace
  metric_name = aws_cloudwatch_log_metric_filter.integrity_violations.metric_transformation[0].name
  statistic   = "Maximum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}

# ---- タスクが止まった ----
# アラームではなく、ECS のイベントを EventBridge のルールで受けて送る。Container Insights（有料）なしでは、動いているタスクの数を測れない。
# 知らせるのは、コンテナが自分で終わった（落ちた、メモリ不足）、起動に失敗した、ヘルスチェックに落ちた、の 3 つ。
# デプロイの入れ替えと夜間の停止は、ECS が止める（ServiceSchedulerInitiated）。知らせない。
# マイグレーションの単発タスクは、サービスに属さない（group が違う）。終わるのがふつうなので、知らせない。
# 落ちては起動し直すことを繰り返すと、そのたびにメールが届く。デプロイの失敗なら、サーキットブレーカーが数回で止める

resource "aws_cloudwatch_event_rule" "task_stopped" {
  name        = "${var.name}-quiz-service-task-stopped"
  description = "quiz-service のタスクが、ECS の指示によらずに止まった"

  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.this.arn]
      group      = ["service:${aws_ecs_service.app.name}"]
      lastStatus = ["STOPPED"]
      "$or" = [
        { stopCode = ["EssentialContainerExited", "TaskFailedToStart"] },
        { stoppedReason = [{ prefix = "Task failed container health checks" }] },
      ]
    }
  })
}

resource "aws_cloudwatch_event_target" "task_stopped" {
  rule = aws_cloudwatch_event_rule.task_stopped.name
  arn  = var.alarm_topic_arn

  # イベントの JSON をそのまま送ると、メールで読みにくい。要点だけを文にする
  input_transformer {
    input_paths = {
      time   = "$.time"
      code   = "$.detail.stopCode"
      reason = "$.detail.stoppedReason"
      task   = "$.detail.taskArn"
    }
    input_template = "\"quiz-service のタスクが止まりました（<time>）。理由: <reason>（<code>）。タスク: <task>。CloudWatch Logs の /ecs/${var.name}/quiz-service で、止まる前のログを見る。手順: ${local.runbook.task_stopped}\""
  }
}
