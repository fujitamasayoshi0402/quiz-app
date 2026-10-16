# 使われなくなったら、サービスを止める（DEV-153）。人が使っていない時間のタスクの費用を払わないため。
#
# API Gateway への要求が var.idle_stop_minutes の間ないと、アラームが ALARM になる。
# EventBridge がその変化を受け、Step Functions が ECS の UpdateService でタスク数を 0 にする。
# 判断は少ないので、コードは書かず、SDK の連携で ECS を直接呼ぶ（深夜の停止と同じ考え方。schedule.tf）。
#
# - デプロイの途中なら止めない
# - 起動してから var.idle_stop_minutes たっていないタスクは、すぐには止めない。残りの時間だけ待ち、アラームがまだ ALARM なら止める。
#   アラームは最後の要求から数えるため、最後の要求から間もないうちに起こすと（深夜の停止の直後など）、起こしてすぐに止まる
# - 要求の数は、認証していない要求（401）も含む。dev では、使わない日のボットからの要求は 1 日数件で、止まるのを妨げない
# - アラームは状態が変わったときにしか動かない。止まったあと（ALARM のまま）に起こし、一度も要求がないまま置くと止まらない。深夜の停止（schedule.tf）が拾う
# - 止まっている間、API への要求はタスクに届かない（API Gateway が返す）。要求は数えられるが、起こしはしない
# - アラームとしては通知しない（SNS につながない）。ALARM はふつうの状態で、異常ではない

locals {
  idle_stop = var.idle_stop_minutes != null
}

resource "aws_cloudwatch_metric_alarm" "idle" {
  count = local.idle_stop ? 1 : 0

  alarm_name        = "${var.name}-quiz-service-idle"
  alarm_description = "API への要求が ${var.idle_stop_minutes} 分ありません。サービスのタスク数を 0 にします（異常ではありません）"

  namespace   = "AWS/ApiGateway"
  metric_name = "Count"
  dimensions = {
    ApiId = aws_apigatewayv2_api.this.id
    Stage = aws_apigatewayv2_stage.default.name
  }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = var.idle_stop_minutes / 5
  datapoints_to_alarm = var.idle_stop_minutes / 5
  comparison_operator = "LessThanThreshold"
  threshold           = 1

  # 要求がない間、API Gateway はメトリクスを出さない。出ないことを「要求が 0」とみなす
  treat_missing_data = "breaching"
}

resource "aws_cloudwatch_event_rule" "idle" {
  count = local.idle_stop ? 1 : 0

  name        = "${var.name}-quiz-service-idle"
  description = "使われていないアラームが ALARM になったら、サービスを止める"

  event_pattern = jsonencode({
    source        = ["aws.cloudwatch"]
    "detail-type" = ["CloudWatch Alarm State Change"]
    resources     = [aws_cloudwatch_metric_alarm.idle[0].arn]
    detail        = { state = { value = ["ALARM"] } }
  })
}

resource "aws_cloudwatch_event_target" "idle" {
  count = local.idle_stop ? 1 : 0

  rule     = aws_cloudwatch_event_rule.idle[0].name
  arn      = aws_sfn_state_machine.idle_stop[0].arn
  role_arn = aws_iam_role.idle_stop_events[0].arn
}

# ---- 止める手順 ----

resource "aws_sfn_state_machine" "idle_stop" {
  count = local.idle_stop ? 1 : 0

  name = "${var.name}-quiz-service-idle-stop"
  # 起動したばかりのタスクを待つため、Standard にする。Express は 5 分までしか動けない
  type     = "STANDARD"
  role_arn = aws_iam_role.idle_stop[0].arn

  definition = jsonencode({
    Comment       = "使われていない quiz-service のタスク数を 0 にする。デプロイの途中なら止めない。起動したばかりなら待ってから確かめ直す"
    QueryLanguage = "JSONata"
    StartAt       = "DescribeService"
    States = {
      DescribeService = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:describeServices"
        Arguments = { Cluster = aws_ecs_cluster.this.name, Services = [aws_ecs_service.app.name] }
        Output = {
          desiredCount = "{% $states.result.Services[0].DesiredCount %}"
          # 入れ替えの途中に止めると、デプロイのジョブが失敗する
          deploying = "{% $count($states.result.Services[0].Deployments[RolloutState = 'IN_PROGRESS']) > 0 %}"
        }
        Next = "ShouldStop"
      }
      ShouldStop = {
        Type = "Choice"
        Choices = [
          { Condition = "{% $states.input.deploying %}", Next = "Deploying" },
          { Condition = "{% $states.input.desiredCount = 0 %}", Next = "AlreadyStopped" },
        ]
        Default = "ListTasks"
      }
      ListTasks = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:listTasks"
        Arguments = { Cluster = aws_ecs_cluster.this.name, ServiceName = aws_ecs_service.app.name, DesiredStatus = "RUNNING" }
        Output    = { taskArns = "{% $states.result.TaskArns %}" }
        Next      = "HasTasks"
      }
      HasTasks = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $count($states.input.taskArns) > 0 %}", Next = "TaskAge" }]
        Default = "Stop"
      }
      # 最も新しいタスクが起動してから、まだ足りない秒数。起動の途中（StartedAt がない）なら、いま起動したとみなす
      TaskAge = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:describeTasks"
        Arguments = { Cluster = aws_ecs_cluster.this.name, Tasks = "{% $states.input.taskArns %}" }
        Output = {
          waitSeconds = "{% ( $now := $toMillis($now()); $started := [$states.result.Tasks.($exists(StartedAt) ? $toMillis(StartedAt) : $now)]; $max([0, $round((${var.idle_stop_minutes * 60000} - ($now - $max($started))) / 1000)]) ) %}"
        }
        Next = "OldEnough"
      }
      OldEnough = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $states.input.waitSeconds > 0 %}", Next = "WaitForTask" }]
        Default = "Stop"
      }
      WaitForTask = {
        Type    = "Wait"
        Seconds = "{% $states.input.waitSeconds %}"
        Next    = "StillIdle"
      }
      # 待っている間に使われたら、アラームは OK に戻っている
      StillIdle = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:cloudwatch:describeAlarms"
        Arguments = { AlarmNames = [aws_cloudwatch_metric_alarm.idle[0].alarm_name] }
        Output    = { alarm = "{% $states.result.MetricAlarms[0].StateValue %}" }
        Next      = "IdleAfterWait"
      }
      IdleAfterWait = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $states.input.alarm = 'ALARM' %}", Next = "DescribeService" }]
        Default = "InUse"
      }
      Stop = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:updateService"
        Arguments = { Cluster = aws_ecs_cluster.this.name, Service = aws_ecs_service.app.name, DesiredCount = 0 }
        Output    = { stopped = true }
        End       = true
      }
      Deploying      = { Type = "Succeed", Output = { stopped = false, reason = "deploying" } }
      AlreadyStopped = { Type = "Succeed", Output = { stopped = false, reason = "already-stopped" } }
      InUse          = { Type = "Succeed", Output = { stopped = false, reason = "in-use" } }
    }
  })

  # 止めたかどうかと理由（出力）を、Logs Insights でも追えるようにする。実行の履歴はコンソールでも見られる（90 日）
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.idle_stop[0].arn}:*"
    include_execution_data = true
    level                  = "ALL"
  }
}

resource "aws_cloudwatch_log_group" "idle_stop" {
  count = local.idle_stop ? 1 : 0

  name              = "/aws/vendedlogs/states/${var.name}-quiz-service-idle-stop"
  retention_in_days = var.log_retention_days
}

# ---- ロール ----

# Step Functions: このサービスとタスク、使われていないアラームを調べて、タスク数を変えることだけを許す
data "aws_iam_policy_document" "idle_stop_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "idle_stop" {
  count = local.idle_stop ? 1 : 0

  name               = "${var.name}-quiz-service-idle-stop"
  assume_role_policy = data.aws_iam_policy_document.idle_stop_assume.json
}

data "aws_iam_policy_document" "idle_stop" {
  statement {
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [aws_ecs_service.app.id]
  }

  # ListTasks はタスクを ARN で指定できない。クラスタで絞る
  statement {
    actions   = ["ecs:ListTasks"]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.this.arn]
    }
  }

  statement {
    actions   = ["ecs:DescribeTasks"]
    resources = ["arn:aws:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:task/${aws_ecs_cluster.this.name}/*"]
  }

  statement {
    actions   = ["cloudwatch:DescribeAlarms"]
    resources = aws_cloudwatch_metric_alarm.idle[*].arn
  }

  # ログの配信の設定は、ロググループを指定できない（Step Functions の仕様）
  statement {
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "idle_stop" {
  count = local.idle_stop ? 1 : 0

  name   = "stop-idle-service"
  role   = aws_iam_role.idle_stop[0].id
  policy = data.aws_iam_policy_document.idle_stop.json
}

# EventBridge: この手順を始めることだけを許す
data "aws_iam_policy_document" "idle_stop_events_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "idle_stop_events" {
  count = local.idle_stop ? 1 : 0

  name               = "${var.name}-quiz-service-idle-stop-events"
  assume_role_policy = data.aws_iam_policy_document.idle_stop_events_assume.json
}

resource "aws_iam_role_policy" "idle_stop_events" {
  count = local.idle_stop ? 1 : 0

  name = "start-idle-stop"
  role = aws_iam_role.idle_stop_events[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "states:StartExecution"
      Resource = aws_sfn_state_machine.idle_stop[0].arn
    }]
  })
}
