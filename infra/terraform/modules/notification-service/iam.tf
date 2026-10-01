# 関数のロール。どれも、このサービスが使うものだけに絞る

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    # 他のアカウントの Lambda に、このロールを使わせない
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = local.function_name
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

data "aws_iam_policy_document" "this" {
  # テナントの Webhook の URL を読む。**読めるのはこの関数だけ**で、quiz-service は書くことと消すことしかできない。
  # 暗号化は AWS 管理のキー（aws/ssm）。キーのポリシーが SSM を通した利用を許すため、kms の権限は要らない
  statement {
    sid       = "ReadWebhookUrls"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${var.webhook_parameter_prefix}/tenants/*/slack-webhook-url"]
  }

  statement {
    sid       = "Deliveries"
    actions   = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.deliveries.arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }

  # 再試行が尽きたイベントを、失敗時の送り先（DLQ）に置く
  statement {
    sid       = "SendFailures"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.dlq.arn]
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "notify-slack"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.this.json
}
