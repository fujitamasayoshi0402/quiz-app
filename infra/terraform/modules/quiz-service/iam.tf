# タスクのロール。どれも必要なものだけを、対象を絞って許す。
#
# | ロール         | 使う者                         | 許すこと                                                     |
# | -------------- | ------------------------------ | ------------------------------------------------------------ |
# | execution      | ECS（タスクを起動するとき）    | イメージの取得、ログの書き込み、図の署名の秘密鍵の読み出し   |
# | app            | quiz-service                   | DB に quiz_app として接続する、解説図の読み書き              |
# | migrate        | マイグレーションの単発タスク   | DB に quiz（スキーマ所有者）として接続する                   |
#
# DB のパスワードは存在しない（ADR-0014）。どのロールで接続できるかは rds-db:connect で決まる。
# Data API やマスターユーザーのシークレットは、どのタスクにも与えない。

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # 他のアカウントの ECS に、このロールを使わせない
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

# ---- execution ----

resource "aws_iam_role" "execution" {
  name               = "${var.name}-quiz-service-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# AWS 管理の AmazonECSTaskExecutionRolePolicy は、すべてのリポジトリとロググループが対象になる。
# このサービスのものに絞って書く
data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PullImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [aws_ecr_repository.quiz_service.arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.quiz_service.arn}:*"]
  }

  # アプリのタスクの secrets に渡す（ADR-0017）。AWS 管理のキー（aws/ssm）で暗号化しているため、kms:Decrypt は要らない
  statement {
    sid       = "ReadFigureSigningKey"
    actions   = ["ssm:GetParameters"]
    resources = [var.figures.private_key_parameter_arn]
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "pull-image-and-write-logs"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# ---- app ----

resource "aws_iam_role" "app" {
  name               = "${var.name}-quiz-service-app"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "app" {
  name = "connect-as-quiz-app"
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "rds-db:connect"
      Resource = var.iam_db_user_arns.app
    }]
  })
}

# 解説図の原本と SVG（ADR-0017）。一覧（ListBucket）は与えない。図はいつも DB の行から ID で引く
resource "aws_iam_role_policy" "app_figures" {
  name = "read-write-figures"
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
      Resource = [
        "${var.figures.bucket_arn}/svg/*",
        "${var.figures.bucket_arn}/drawio/*",
      ]
    }]
  })
}

# ---- migrate ----

resource "aws_iam_role" "migrate" {
  name               = "${var.name}-quiz-service-migrate"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "migrate" {
  name = "connect-as-schema-owner"
  role = aws_iam_role.migrate.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "rds-db:connect"
      Resource = var.iam_db_user_arns.migrate
    }]
  })
}

data "aws_caller_identity" "current" {}
