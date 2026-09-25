# 夜間にサービスを止め、朝に戻す（DEV-51）。人が使わない時間のタスクの費用を払わないため。
#
# EventBridge Scheduler から ECS の UpdateService を直接呼び、タスク数だけを変える。止める・戻す以外の判断はしないので、Lambda は挟まない。
# 止まっている間、API は ALB が 503 を返す。Aurora は接続がなくなれば自分で一時停止する。
# ALB は止められない（止まっている間も課金が続く）。
#
# 止まっている間にデプロイすると、デプロイが 1 つ起動してから載せる（.github/scripts/deploy-quiz-service.sh）。
# その場合も、次の停止の時刻にまた止まる。

locals {
  schedules = var.nightly_stop == null ? {} : {
    stop  = { expression = var.nightly_stop.stop, desired_count = 0 }
    start = { expression = var.nightly_stop.start, desired_count = var.desired_count }
  }
}

resource "aws_scheduler_schedule" "service" {
  for_each = local.schedules

  name                         = "${var.name}-quiz-service-${each.key}"
  schedule_expression          = each.value.expression
  schedule_expression_timezone = var.nightly_stop.timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler[0].arn

    input = jsonencode({
      Cluster      = aws_ecs_cluster.this.name
      Service      = aws_ecs_service.app.name
      DesiredCount = each.value.desired_count
    })

    # 既定では 24 時間まで再試行する。止めるのに失敗したまま、昼になってから止まることがないように、1 時間で諦める
    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 3
    }
  }
}

# ---- ロール ----
# このサービスのタスク数を変えることだけを許す

data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  count = var.nightly_stop == null ? 0 : 1

  name               = "${var.name}-quiz-service-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    actions   = ["ecs:UpdateService"]
    resources = [aws_ecs_service.app.id]
  }
}

resource "aws_iam_role_policy" "scheduler" {
  count = var.nightly_stop == null ? 0 : 1

  name   = "update-desired-count"
  role   = aws_iam_role.scheduler[0].id
  policy = data.aws_iam_policy_document.scheduler.json
}
