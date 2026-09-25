# タスクのロール。どれも必要なものだけを、対象を絞って許す。
#
# | ロール         | 使う者                         | 許すこと                                      |
# | -------------- | ------------------------------ | --------------------------------------------- |
# | execution      | ECS（タスクを起動するとき）    | イメージの取得、ログの書き込み                |
# | app            | quiz-service                   | DB に quiz_app として接続する                 |
# | migrate        | マイグレーションの単発タスク   | DB に quiz（スキーマ所有者）として接続する    |
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
