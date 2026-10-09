# notification-service（ADR-0022）。クイズのイベントを受けて、テナントが設定した Slack に知らせる Lambda。
#
#   カスタムバス（modules/events）→ ルール（通知するものだけ）→ Lambda（非同期。VPC の外）
#     ├ SSM Parameter Store: テナントの Webhook の URL を読む（無ければ何もしない）
#     ├ DynamoDB: イベントの ID で重複を捨てる
#     └ Slack（Incoming Webhook）
#   送れなかったもの（Lambda の再試行が尽きた / ルールから Lambda に届かなかった）→ SQS（DLQ）→ アラーム → メール
#
# VPC の外に置く。Slack、SSM、DynamoDB へ NAT なしで出られ、Aurora には触れないため、通知が DB を起こさない。
# 関数のコードは、作るときにだけ var.package_path から載せる。以降は Terraform では変えない（開発ガイドライン「通知（notification-service）」）

locals {
  function_name = "${var.name}-notification-service"
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "this" {
  function_name = local.function_name
  role          = aws_iam_role.this.arn

  runtime       = "java21"
  architectures = ["arm64"]
  handler       = "com.quizapp.notification.Handler::handleRequest"
  filename      = var.package_path

  memory_size = var.memory_size
  # Slack を待つのは 10 秒まで（HttpSlack）。重複を捨てる記録の処理中の期限（1 分、DynamoDbDeliveries）より短くする
  timeout = 30

  environment {
    variables = {
      WEBHOOK_PARAMETER_PREFIX = var.webhook_parameter_prefix
      DELIVERIES_TABLE         = aws_dynamodb_table.deliveries.name
      WEB_BASE_URL             = var.web_base_url
      SLACK_DELIVERY           = "send"
      # 1 回の呼び出しは短く、JIT の最適化を待つより起動の速さが効く（AWS が Java の Lambda に勧める設定）
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.this.name
  }

  # 分散トレース（ADR-0026）。quiz-service が PutEvents に付けたトレースヘッダを EventBridge が渡し、
  # この関数の区間が、イベントを書いた要求のトレースにつながる。記録するかは、quiz-service の判定に従う
  tracing_config {
    mode = "Active"
  }

  # コードは Terraform の外で載せ替える。ここで追うと、zip が手元にない人の plan が失敗し、載せ替えたものを戻そうともする
  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }

  depends_on = [aws_iam_role_policy.this]
}

# ルールからの非同期の呼び出し。失敗したら 2 回まで再試行し、尽きたら DLQ に置く
resource "aws_lambda_function_event_invoke_config" "this" {
  function_name          = aws_lambda_function.this.function_name
  maximum_retry_attempts = 2

  destination_config {
    on_failure {
      destination = aws_sqs_queue.dlq.arn
    }
  }
}

# ---- 重複を捨てる記録 ----

# 中身はイベントの ID と状態だけ。TTL で 7 日後に消える（バスのアーカイブの保持期間と揃える）
resource "aws_dynamodb_table" "deliveries" {
  name         = "${var.name}-notification-deliveries"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "eventId"

  attribute {
    name = "eventId"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
}

# ---- ルール ----

# 何を通知するかは、受け手のルールが決める（ADR-0022 の 1）。中身は event-pattern.json で、EventPatternTest が確かめる
resource "aws_cloudwatch_event_rule" "this" {
  name           = "${var.name}-notify-slack"
  description    = "公開中のクイズに関わるイベントを、notification-service に送る"
  event_bus_name = var.event_bus_name
  event_pattern  = jsonencode(jsondecode(file("${path.module}/event-pattern.json")))
}

resource "aws_cloudwatch_event_target" "this" {
  rule           = aws_cloudwatch_event_rule.this.name
  event_bus_name = var.event_bus_name
  arn            = aws_lambda_function.this.arn

  # Lambda まで届かなかったもの（権限の誤りなど）も、同じ DLQ に置く
  dead_letter_config {
    arn = aws_sqs_queue.dlq.arn
  }
}

resource "aws_lambda_permission" "events" {
  statement_id  = "AllowQuizEventsRule"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.this.arn
}

# ---- 送れなかったもの ----

# イベント（問題文の冒頭を含む）がそのまま入る。直したあとに、中身を見てアーカイブから流し直す
resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-notification-dlq"
  message_retention_seconds = 14 * 24 * 3600
  sqs_managed_sse_enabled   = true
}

# ルールのターゲットの DLQ は、EventBridge が自分の権限で置く。このルールからだけ許す。
# Lambda の失敗時の送り先は、関数のロールで置く（iam.tf）
resource "aws_sqs_queue_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.dlq.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.this.arn } }
    }]
  })
}

# **1 件でも入ったら知らせる。** 黙って捨てない（ADR-0022 の 6）。送り先は環境で共通のトピック（modules/alarms）
resource "aws_cloudwatch_metric_alarm" "dlq" {
  alarm_name        = "${var.name}-notification-dlq-not-empty"
  alarm_description = "Slack に知らせられなかったイベントが DLQ（${aws_sqs_queue.dlq.name}）にあります。手順: ${var.runbook_url}#${urlencode("通知が-dlq-に入った")}"

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible"
  dimensions  = { QueueName = aws_sqs_queue.dlq.name }
  statistic   = "Maximum"
  period      = 300

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  # 空のキューは、しばらくするとメトリクスを出さなくなる
  treat_missing_data = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}
