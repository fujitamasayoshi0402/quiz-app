# quiz-service を API Gateway（HTTP API）で公開する（ADR-0019）。web は Amplify Hosting から、ここを呼ぶ（ADR-0012）。
#
#   web の proxy → API Gateway（api.<環境>.<ドメイン>）→ VPC リンク → Cloud Map で見つけたタスク
#
# ロードバランサーを置かない。HTTP API はリクエストの数で課金され、VPC リンクと Cloud Map に時間あたりの料金はほぼない。
# 以前の ALB は、使っていなくても月に約 25 ドル（パブリック IPv4 を含む）かかっていた。
#
# **入口では認証しない。** アクセストークンの検証はアプリが行う（ADR-0016）。
# 入口でも検証すると、検証が 2 か所に分かれ、ローカル（API Gateway がない）と AWS で経路が変わる。

# ---- タスクの見つけ方（Cloud Map） ----
# ECS がタスクの IP とポートを登録し、API Gateway が DiscoverInstances で引く。
# API Gateway はポートを知る必要があるため、SRV レコードで登録する（A レコードだけではポートが載らない）

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = "${var.name}.internal"
  description = "Service discovery for ${var.name}"
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "quiz_service" {
  name = "quiz-service"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "SRV"
      ttl  = 10
    }
  }

  # タスクの状態を ECS が Cloud Map に伝える。コンテナのヘルスチェック（ecs.tf）が通るまで UNHEALTHY で、
  # API Gateway は要求を送らない。起動の途中の JVM に要求が届かない。
  # failure_threshold は非推奨で、AWS は値を使わない（常に 1）。空のブロックにすると provider が設定を送らないため、書いている
  health_check_custom_config {
    failure_threshold = 1
  }

  # ECS が登録したインスタンスが残っていても消せるようにする。環境ごと作り直すときに止まらない
  force_destroy = true
}

# ---- VPC リンク ----
# API Gateway が VPC の中に ENI を置き、そこからタスクへ届ける。外へ出る必要がないので、プライベートサブネットに置く

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = var.name
  subnet_ids         = var.vpc_link_subnet_ids
  security_group_ids = [var.vpc_link_security_group_id]
}

# ---- HTTP API ----

resource "aws_apigatewayv2_api" "this" {
  name          = var.name
  protocol_type = "HTTP"

  # 既定のエンドポイント（*.execute-api.amazonaws.com）を閉じ、独自ドメインからだけ受ける
  disable_execute_api_endpoint = true
}

resource "aws_apigatewayv2_integration" "quiz_service" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  connection_type    = "VPC_LINK"
  connection_id      = aws_apigatewayv2_vpc_link.this.id
  integration_uri    = aws_service_discovery_service.quiz_service.arn

  # HTTP API の上限。止まっている Aurora への最初の要求は約 20 秒かかる（DEV-50）。
  # 間に合わなければ 504 が返り、画面が再試行する（apps/web/src/app/providers.tsx）
  timeout_milliseconds = 30000

  # パスをそのまま渡す。ステージの名前がパスに入る構成にしても、タスクに届くパスが変わらないように。
  # 要求の ID をタスクに渡す。アプリはこれを自分のログの要求の ID にし（RequestLogFilter）、アクセスログの requestId と結び付く。
  # 上書きするので、外から送られた値はタスクに届かない
  request_parameters = {
    "overwrite:path"                = "$request.path"
    "overwrite:header.x-request-id" = "$context.requestId"
  }
}

# /api の下だけを通す。アクチュエータ（ヘルスチェック）などは外から届かず、API Gateway が 404 を返す
resource "aws_apigatewayv2_route" "api" {
  api_id    = aws_apigatewayv2_api.this.id
  route_key = "ANY /api/{proxy+}"
  target    = "integrations/${aws_apigatewayv2_integration.quiz_service.id}"
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  # 叩かれ続けても、費用に上限を付ける。20 件/秒で 1 か月続いても約 5,200 万回（約 67 ドル）で、予算の通知が先に届く。
  # **API 全体に 1 つ。** 1 人がこれを使い切ると全員が 429 になるため、quiz-service が利用者ごとの上限（5 件/秒、バースト 30）を掛ける（DEV-125、RateLimitProperties）。
  # 利用者ごとの上限より十分大きくする。同じだと、1 人で全員を止められる。
  # アクセストークンのない要求は利用者ごとには数えられず、ここで受ける
  default_route_settings {
    throttling_rate_limit  = 20
    throttling_burst_limit = 60
  }

  # パスは残さない。招待の受け入れ（/api/me/invitations/{token}）のパスにはトークンが入る。
  # DB にもハッシュしか持たない値を、ログに平文で残さない
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      requestTime        = "$context.requestTime"
      sourceIp           = "$context.identity.sourceIp"
      method             = "$context.httpMethod"
      status             = "$context.status"
      responseLength     = "$context.responseLength"
      responseLatency    = "$context.responseLatency"
      integrationStatus  = "$context.integrationStatus"
      integrationLatency = "$context.integrationLatency"
      integrationError   = "$context.integrationErrorMessage"
    })
  }
}

# ---- 独自ドメイン ----
# 証明書は dns.tf のもの。HTTP API で選べる TLS のポリシーは TLS_1_2 だけで、TLS 1.2 と 1.3 を受ける

resource "aws_apigatewayv2_domain_name" "api" {
  domain_name = var.api_domain_name

  domain_name_configuration {
    certificate_arn = aws_acm_certificate_validation.api.certificate_arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
}

resource "aws_apigatewayv2_api_mapping" "api" {
  api_id      = aws_apigatewayv2_api.this.id
  domain_name = aws_apigatewayv2_domain_name.api.id
  stage       = aws_apigatewayv2_stage.default.id
}
