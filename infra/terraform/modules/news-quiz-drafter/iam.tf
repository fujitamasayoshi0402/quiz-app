# 関数と、起動するスケジュールのロール。どれも、この関数が使うものだけに絞る

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

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

locals {
  inference_profile_arn = "arn:aws:bedrock:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.model_id}"
}

data "aws_iam_policy_document" "this" {
  # **Bedrock を呼べるのは、このロールだけ**（ADR-0031）。モデルは 1 つに限る。
  # グローバルの推論プロファイルは、どのリージョンの基盤モデルにも振り分ける。基盤モデルの権限は、
  # このプロファイルを通したときだけに限る
  statement {
    sid       = "InvokeThroughProfile"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.inference_profile_arn]
  }

  statement {
    sid     = "InvokeFoundationModel"
    actions = ["bedrock:InvokeModel"]
    resources = [
      "arn:aws:bedrock:*::foundation-model/${var.foundation_model_id}",
      "arn:aws:bedrock:::foundation-model/${var.foundation_model_id}",
    ]
    condition {
      test     = "StringEquals"
      variable = "bedrock:InferenceProfileArn"
      values   = [local.inference_profile_arn]
    }
  }

  # 受け箱に置き、数える。読んだ発表の記録を読み書きする
  statement {
    sid       = "WriteInbox"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this.arn}/inbox/*"]
  }

  statement {
    sid       = "State"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.this.arn}/state/*"]
  }

  # 一覧は受け箱を数えるため。記録が無いとき（初回）に、403 ではなく 404 を受け取るためにも要る
  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.this.arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "news-quiz-drafter"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.this.json
}

# スケジュールが関数を呼ぶロール
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
  name               = "${local.function_name}-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

resource "aws_iam_role_policy" "scheduler" {
  name = "invoke-news-quiz-drafter"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "lambda:InvokeFunction"
      Resource = aws_lambda_function.this.arn
    }]
  })
}
