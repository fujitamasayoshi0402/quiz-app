# アラームの送り先（DEV-108）。環境に 1 つ置き、各モジュールのアラームがここへ送る。
# どのアラームを置いているか、鳴ったら何を見るかは、開発ガイドラインの「アラーム」にある。
#
# 送るのは CloudWatch のアラームと、EventBridge のルール（ECS のタスクが止まったなど）。予算（AWS Budgets）も送れる（account）。
# 既定のトピックのポリシーは、EventBridge からの送信を許さない。送る側をすべて許すポリシーを置く
#
# **メールは日本語にしてから送る**（DEV-214）。AWS が送るメールは、件名と本文の枠が英語に固定され、何が起きたか分からない。
#
#   アラーム・ルール・予算 → このトピック → Lambda（件名と本文を日本語に組み立てる）→ メールのトピック → メール
#
# EventBridge の入力の変換では本文しか替えられず、件名は「AWS Notification Message」に固定される。
# Lambda はコードを持つが、依存は Lambda の Node.js が持つ AWS SDK だけで、Terraform が zip にして載せる

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
}

resource "aws_sns_topic" "this" {
  name = "${var.name}-alarms"
}

resource "aws_sns_topic_policy" "this" {
  arn = aws_sns_topic.this.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "CloudWatchAlarms"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.this.arn
        Condition = {
          ArnLike = { "aws:SourceArn" = "arn:aws:cloudwatch:${local.region}:${local.account_id}:alarm:${var.name}-*" }
        }
      },
      {
        # ルールは各モジュールが作る。名前の頭で絞る
        Sid       = "EventBridgeRules"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.this.arn
        Condition = {
          ArnLike = { "aws:SourceArn" = "arn:aws:events:${local.region}:${local.account_id}:rule/${var.name}-*" }
        }
      },
      ], var.allow_budgets ? [{
        Sid       = "Budgets"
        Effect    = "Allow"
        Principal = { Service = "budgets.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.this.arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = local.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:budgets::${local.account_id}:*" }
        }
    }] : [])
  })
}

# ---- メール ----

resource "aws_sns_topic" "mail" {
  name = "${var.name}-alarm-mail"
}

# メールの購読は、届いた確認のメールのリンクを開くまで有効にならない
resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.mail.arn
  protocol  = "email"
  endpoint  = var.email
}

# ---- 日本語にする Lambda ----

data "archive_file" "mailer" {
  type        = "zip"
  source_dir  = "${path.module}/mailer"
  excludes    = ["format.test.mjs"]
  output_path = "${path.root}/.terraform/${var.name}-alarm-mailer.zip"
}

resource "aws_cloudwatch_log_group" "mailer" {
  name              = "/aws/lambda/${var.name}-alarm-mailer"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "mailer" {
  name = "${var.name}-alarm-mailer"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "mailer" {
  role = aws_iam_role.mailer.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.mailer.arn}:*"
      },
      {
        # 送れるのは、メールのトピックだけ。受けるトピックへ送ると、自分に戻ってくる
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = aws_sns_topic.mail.arn
      },
    ]
  })
}

resource "aws_lambda_function" "mailer" {
  function_name    = "${var.name}-alarm-mailer"
  role             = aws_iam_role.mailer.arn
  runtime          = "nodejs22.x"
  architectures    = ["arm64"]
  handler          = "index.handler"
  filename         = data.archive_file.mailer.output_path
  source_code_hash = data.archive_file.mailer.output_base64sha256
  memory_size      = 128
  timeout          = 10

  environment {
    variables = {
      LABEL          = var.label
      MAIL_TOPIC_ARN = aws_sns_topic.mail.arn
    }
  }

  depends_on = [aws_cloudwatch_log_group.mailer, aws_iam_role_policy.mailer]
}

resource "aws_lambda_permission" "mailer" {
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.mailer.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.this.arn
}

resource "aws_sns_topic_subscription" "mailer" {
  topic_arn = aws_sns_topic.this.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.mailer.arn

  depends_on = [aws_lambda_permission.mailer]
}
