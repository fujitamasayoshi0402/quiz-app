# 止まっているサービスを起こす（DEV-154）。使われなくなったら止める環境（idle_stop.tf）にだけ置く。
#
# 画面の「起動する」→ web の proxy（アクセストークンを付ける）→ API Gateway の POST /api/wake → Step Functions → ECS の UpdateService
#
# - タスクが止まっていても届くよう、API Gateway が Step Functions を直接起動する。quiz-service を通らない
# - **ログインした人だけが起こせる。** アクセストークンは API Gateway が確かめる（JWT の認可）。quiz-service と同じく、発行者と web のクライアントを見る。
#   誰でもサインアップできるため、起こせる人は絞らない。起こしても、使われなければ止まる（idle_stop.tf）
# - 何度呼ばれても、タスクは 1 つより増えない。止まっているときだけ起こす
# - 流量の上限は、ほかの API より低くする（api.tf の route_settings）。叩き続けても、費用はタスク 1 つ分を超えない
# - 電話から起こすとき（DEV-152）も、同じステートマシンを呼ぶ

resource "aws_apigatewayv2_authorizer" "cognito" {
  count = local.idle_stop ? 1 : 0

  api_id           = aws_apigatewayv2_api.this.id
  name             = "cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  # Cognito のアクセストークンには aud がない。API Gateway は aud がなければ client_id を audience と比べる
  jwt_configuration {
    issuer   = var.auth_issuer
    audience = [var.auth_client_id]
  }
}

resource "aws_apigatewayv2_integration" "wake" {
  count = local.idle_stop ? 1 : 0

  api_id              = aws_apigatewayv2_api.this.id
  integration_type    = "AWS_PROXY"
  integration_subtype = "StepFunctions-StartExecution"
  credentials_arn     = aws_iam_role.wake_api[0].arn

  request_parameters = {
    StateMachineArn = aws_sfn_state_machine.wake[0].arn
  }
}

# ANY /api/{proxy+} より先に当たる。ほかのメソッドの /api/wake は quiz-service に届き、404 になる
resource "aws_apigatewayv2_route" "wake" {
  count = local.idle_stop ? 1 : 0

  api_id             = aws_apigatewayv2_api.this.id
  route_key          = local.wake_route_key
  target             = "integrations/${aws_apigatewayv2_integration.wake[0].id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito[0].id
}

locals {
  wake_route_key = "POST /api/wake"
}

# ---- 起こす手順 ----

resource "aws_sfn_state_machine" "wake" {
  count = local.idle_stop ? 1 : 0

  name     = "${var.name}-quiz-service-wake"
  type     = "EXPRESS"
  role_arn = aws_iam_role.wake[0].arn

  definition = jsonencode({
    Comment       = "止まっている quiz-service を起こす。動いていれば何もしない"
    QueryLanguage = "JSONata"
    StartAt       = "DescribeService"
    States = {
      DescribeService = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:describeServices"
        Arguments = { Cluster = aws_ecs_cluster.this.name, Services = [aws_ecs_service.app.name] }
        Output    = { desiredCount = "{% $states.result.Services[0].DesiredCount %}" }
        Next      = "IsStopped"
      }
      IsStopped = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $states.input.desiredCount = 0 %}", Next = "Start" }]
        Default = "AlreadyRunning"
      }
      Start = {
        Type      = "Task"
        Resource  = "arn:aws:states:::aws-sdk:ecs:updateService"
        Arguments = { Cluster = aws_ecs_cluster.this.name, Service = aws_ecs_service.app.name, DesiredCount = var.desired_count }
        Output    = { started = true }
        End       = true
      }
      AlreadyRunning = { Type = "Succeed", Output = { started = false } }
    }
  })

  # Express は実行の履歴を残さない。起こしたかどうかを、ログで追えるようにする
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.wake[0].arn}:*"
    include_execution_data = true
    level                  = "ALL"
  }
}

resource "aws_cloudwatch_log_group" "wake" {
  count = local.idle_stop ? 1 : 0

  name              = "/aws/vendedlogs/states/${var.name}-quiz-service-wake"
  retention_in_days = var.log_retention_days
}

# ---- ロール ----

# Step Functions: このサービスを調べて、タスク数を変えることだけを許す。ロールの信頼は止めるほうと同じ
resource "aws_iam_role" "wake" {
  count = local.idle_stop ? 1 : 0

  name               = "${var.name}-quiz-service-wake"
  assume_role_policy = data.aws_iam_policy_document.idle_stop_assume.json
}

data "aws_iam_policy_document" "wake" {
  statement {
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [aws_ecs_service.app.id]
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

resource "aws_iam_role_policy" "wake" {
  count = local.idle_stop ? 1 : 0

  name   = "wake-service"
  role   = aws_iam_role.wake[0].id
  policy = data.aws_iam_policy_document.wake.json
}

# API Gateway: この手順を始めることだけを許す
data "aws_iam_policy_document" "wake_api_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["apigateway.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "wake_api" {
  count = local.idle_stop ? 1 : 0

  name               = "${var.name}-quiz-service-wake-api"
  assume_role_policy = data.aws_iam_policy_document.wake_api_assume.json
}

resource "aws_iam_role_policy" "wake_api" {
  count = local.idle_stop ? 1 : 0

  name = "start-wake"
  role = aws_iam_role.wake_api[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "states:StartExecution"
      Resource = aws_sfn_state_machine.wake[0].arn
    }]
  })
}
