# web（Next.js）を Amplify Hosting で配る（ADR-0012）。ビルドの手順はリポジトリの amplify.yml にある。
#
#   ブラウザ → Amplify → SSR の proxy（アクセストークンを付ける）→ ALB（秘密のヘッダ）→ quiz-service
#
# 画面は誰でも開ける。データは、ログイン（modules/auth）とテナントの所属で守られる（ADR-0016）。
# API は、ALB が秘密のヘッダで web の proxy からの要求だけを通す（ADR-0012）。

resource "aws_amplify_app" "this" {
  name       = var.name
  repository = var.repository_url
  platform   = "WEB_COMPUTE"

  # GitHub との接続にだけ使う。作成後はトークンを失効させるので、以降の差分は見ない
  access_token = var.github_access_token

  iam_service_role_arn = aws_iam_role.service.arn

  # push のたびにビルドしない。マイグレーションや API のデプロイと順序をそろえるため、起動は手動か CI から行う
  enable_branch_auto_build    = false
  enable_auto_branch_creation = false
  enable_branch_auto_deletion = false

  # SSR の実行時には渡らない。amplify.yml がビルドの中で .env.production に書き出す
  environment_variables = {
    AMPLIFY_MONOREPO_APP_ROOT = "apps/web"
    API_ORIGIN                = var.api_origin
    ORIGIN_VERIFY_SECRET      = var.origin_verify_secret
    AUTH_ISSUER               = var.auth.issuer
    AUTH_CLIENT_ID            = var.auth.client_id
    AUTH_CLIENT_SECRET        = var.auth.client_secret
    AUTH_SESSION_SECRET       = random_password.session.result
  }

  lifecycle {
    ignore_changes = [access_token]
  }
}

resource "aws_amplify_branch" "this" {
  app_id      = aws_amplify_app.this.id
  branch_name = var.branch_name
  framework   = "Next.js - SSR"
  stage       = "DEVELOPMENT"

  enable_auto_build = false

  # ベーシック認証はかけない。スタブ認証の間はかけていた（DEV-46）が、いまは誰にでもなりすませる口がない
  enable_basic_auth = false
}

# トークンを入れる Cookie を暗号化する鍵（ADR-0016）。入れ替えると、ログイン中の全員がログアウトされる
resource "random_password" "session" {
  length  = 64
  special = false
}

# ホストゾーンが同じアカウントの Route 53 にあるため、証明書の検証とサブドメインのレコードは Amplify が作る。
# apex には、このアプリ以外の既存のレコードがある。サブドメインだけを割り当て、apex には触れない
resource "aws_amplify_domain_association" "this" {
  app_id      = aws_amplify_app.this.id
  domain_name = var.domain_name

  enable_auto_sub_domain = false

  # 証明書の発行を待つと、apply が数十分止まることがある。状態は aws amplify get-domain-association で見る
  wait_for_verification = false

  sub_domain {
    branch_name = aws_amplify_branch.this.branch_name
    prefix      = var.subdomain_prefix
  }
}

# ---- サービスロール ----
# SSR の実行ログを CloudWatch Logs に書く。Amplify が使うロググループ（/aws/amplify/）に絞る

resource "aws_iam_role" "service" {
  name = "${var.name}-amplify"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "amplify.amazonaws.com" }
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

resource "aws_iam_role_policy" "service" {
  name = "write-ssr-logs"
  role = aws_iam_role.service.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = [
          "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/amplify/*",
          "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/amplify/*:log-stream:*",
        ]
      },
      {
        Effect   = "Allow"
        Action   = "logs:DescribeLogGroups"
        Resource = "*"
      },
    ]
  })
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}
