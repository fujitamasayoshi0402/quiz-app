# 公式の技術ニュースから、Bedrock でクイズの下書きを作る Lambda（ADR-0031）。
#
#   EventBridge Scheduler（毎日）→ Lambda → S3 の受け箱（inbox/）← quiz-service が使われたときに取り込む
#
# 関数の形（ロール、環境変数、メモリなど）は Terraform が持つ。コードは関数を作るときにだけ zip から読む（notification-service と同じ）

locals {
  function_name = "${var.name}-news-quiz-drafter"
}

# ---- 受け箱と、読んだ発表の記録 ----

resource "aws_s3_bucket" "this" {
  bucket = "${var.name}-news-quizzes"
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 取り込まれないまま残った下書きは、30 日で消す。読んだ発表の記録（state/）は Lambda が自分で古いものを消す
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    id     = "expire-inbox"
    status = "Enabled"
    filter {
      prefix = "inbox/"
    }
    expiration {
      days = 30
    }
  }
}

# ---- 関数 ----

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "this" {
  function_name = local.function_name
  role          = aws_iam_role.this.arn

  runtime       = "java21"
  architectures = ["arm64"]
  handler       = "com.quizapp.newsquiz.Handler::handleRequest"
  filename      = var.package_path

  memory_size = var.memory_size
  # 3 件を選ぶ・書く・確かめる（Bedrock を 7 回ほど呼ぶ）。1 回に数秒〜十数秒かかる
  timeout = 300
  # 同時に 2 つ動かさない。同じ発表から重ねて作り、Bedrock の費用も重なる
  reserved_concurrent_executions = 1

  environment {
    variables = {
      FEED_URL   = var.feed_url
      MODEL_ID   = var.model_id
      BUCKET     = aws_s3_bucket.this.bucket
      MAX_DRAFTS = tostring(var.max_drafts)
      # notification-service と同じく、起動を速くする
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.this.name
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }

  depends_on = [aws_iam_role_policy.this]
}

# 失敗しても、やり直さない。やり直すと、その分 Bedrock を呼ぶ。次の日に、前の回より新しい発表から作る
resource "aws_lambda_function_event_invoke_config" "this" {
  function_name          = aws_lambda_function.this.function_name
  maximum_retry_attempts = 0
}

# ---- 毎日起動する ----

resource "aws_scheduler_schedule" "this" {
  name                         = local.function_name
  description                  = "公式の技術ニュースから、クイズの下書きを作る（ADR-0031）"
  schedule_expression          = var.schedule.expression
  schedule_expression_timezone = var.schedule.timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.this.arn
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({})

    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}

# ---- 失敗を知らせる ----

resource "aws_cloudwatch_metric_alarm" "errors" {
  alarm_name        = "${local.function_name}-errors"
  alarm_description = "技術ニュースから下書きを作る Lambda（${local.function_name}）が失敗しました。その日の下書きは作られていません。手順: ${var.runbook_url}"

  namespace   = "AWS/Lambda"
  metric_name = "Errors"
  dimensions  = { FunctionName = aws_lambda_function.this.function_name }
  statistic   = "Sum"
  period      = 3600

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  # 1 日 1 回しか動かない。動いていない時間は異常ではない
  treat_missing_data = "notBreaching"

  alarm_actions = [var.alarm_topic_arn]
}
