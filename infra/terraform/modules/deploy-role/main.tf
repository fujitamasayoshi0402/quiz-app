# GitHub Actions が dev へのデプロイに使うロール（DEV-48）。アクセスキーは発行せず、OIDC で引き受ける。
#
# デプロイは、イメージの push → マイグレーションの単発タスク → サービスの更新 → web のビルドの順に進む（.github/workflows/deploy-dev.yml）。
# タスク定義の形（環境変数、ロール、CPU など）は Terraform が持ち、CI はイメージだけを差し替えた新しいリビジョンを登録する（ADR-0015）。
# そのため、このロールには Terraform の state も、リソースを作り直す権限も与えない。

# OIDC プロバイダはアカウントに 1 つだけ（infra/terraform/account）
data "aws_iam_openid_connect_provider" "github_actions" {
  url = "https://token.actions.githubusercontent.com"
}

# 引き受けられるのは、指定したリポジトリの、指定した Environment で動くジョブだけ。
# Environment を使うジョブのトークンは、sub が <リポジトリの表記>:environment:<name> になる。
# リポジトリの表記は、名前に所有者とリポジトリの ID が付く（repo:<owner>@<ID>/<name>@<ID>。GitHub の immutable subject）。
# 同じ名前でリポジトリを作り直されても、ID が違うので引き受けられない
# ブランチではなく Environment で絞るのは、どのブランチから使えるかを GitHub 側でまとめて決められるため（dev は develop だけ）
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github_actions.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # StringLike やワイルドカードにしない。repo:* のような広い条件は、他人のリポジトリにも引き受けさせてしまう
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${var.github_subject_prefix}:environment:${var.github_environment}"]
    }
  }
}

resource "aws_iam_role" "this" {
  name                 = "${var.name}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600
}

locals {
  cluster_name  = element(split("/", var.ecs_cluster_arn), 1)
  arn_prefix    = "arn:aws:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}"
  task_def_arns = [for family in values(var.task_definition_families) : "${local.arn_prefix}:task-definition/${family}:*"]
  tasks_arn     = "${local.arn_prefix}:task/${local.cluster_name}/*"
}

data "aws_iam_policy_document" "deploy" {
  # ---- イメージ ----

  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # DescribeImages は、同じコミットのイメージがすでにあるかを見るため。タグは上書きできないので、再実行では作り直さない
  statement {
    sid = "PushImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:DescribeImages",
    ]
    resources = [var.ecr_repository_arn]
  }

  # ---- タスク定義 ----

  # 最新のリビジョンを読み、イメージだけを差し替えて登録し直す。DescribeTaskDefinition は対象を絞れない
  statement {
    sid       = "ReadTaskDefinitions"
    actions   = ["ecs:DescribeTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "RegisterTaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition"]
    resources = local.task_def_arns
  }

  # 登録と起動のときに、Terraform が付けたタグ（Project / Env）を引き継ぐ。タグを付けるだけで、既存のものは書き換えられない
  statement {
    sid       = "TagOnCreate"
    actions   = ["ecs:TagResource"]
    resources = concat(local.task_def_arns, [local.tasks_arn])

    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition", "RunTask"]
    }
  }

  # タスク定義に書かれたロールを ECS に渡す。渡し先を ECS のタスクに限る
  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = var.task_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # ---- マイグレーション ----

  # 流せるのはマイグレーションのタスク定義だけ。アプリのタスク定義を単発で起動させない
  statement {
    sid       = "RunMigration"
    actions   = ["ecs:RunTask"]
    resources = ["${local.arn_prefix}:task-definition/${var.task_definition_families.migrate}:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  statement {
    sid       = "WatchMigration"
    actions   = ["ecs:DescribeTasks"]
    resources = [local.tasks_arn]
  }

  # ---- サービス ----

  # サービスに設定できるのは、アプリのタスク定義だけ
  statement {
    sid       = "UpdateService"
    actions   = ["ecs:UpdateService"]
    resources = [var.ecs_service_arn]

    condition {
      test     = "ArnLike"
      variable = "ecs:task-definition"
      values   = ["${local.arn_prefix}:task-definition/${var.task_definition_families.app}:*"]
    }
  }

  # 入れ替えの進み具合と、マイグレーションに使うネットワークの設定を読む
  statement {
    sid       = "WatchService"
    actions   = ["ecs:DescribeServices"]
    resources = [var.ecs_service_arn]
  }

  # ---- web ----

  # 起動できるのは dev のブランチのビルドだけ。アプリやブランチの設定（環境変数、ベーシック認証）は変えられない
  statement {
    sid       = "BuildWeb"
    actions   = ["amplify:StartJob", "amplify:GetJob"]
    resources = ["${var.amplify_branch_arn}/jobs/*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy-quiz-service"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.deploy.json
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}
