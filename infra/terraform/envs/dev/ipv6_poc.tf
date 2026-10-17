# IPv6 だけのタスクに、API Gateway の VPC リンクから届くかを試す（DEV-183）。確かめたら、このファイルごと消す。
#
# ドキュメントに書かれていないため、2 通りを作って確かめる。
#   A: いまの VPC リンク（IPv4 だけのプライベートサブネット）→ IPv6 だけのタスク
#   B: dual-stack のサブネットに置いた VPC リンク → IPv6 だけのタスク
#
# 試験用の HTTP API（既定のエンドポイント）の GET /a と GET /b が、それぞれを通って nginx に届く。quiz-service の API には触れない。
#
# あわせて確かめること（どれかが駄目なら、タスクが起動しない）
#   - ECR の dualstack の URI から、IPv6 だけでイメージを取れるか
#   - public.ecr.aws（IPv6 を返さない）のイメージを、プルスルーキャッシュで写して取れるか。コレクタのイメージで要る
#   - awslogs のログが、IPv6 だけで CloudWatch Logs に届くか

locals {
  poc_name = "quiz-app-dev-ipv6-poc"

  # IPv6 の /56 から /64 を切り出す。タスク用（IPv6 だけ）は 0、1。VPC リンク B 用（dual-stack）は 10、11
  poc_azs = { "ap-northeast-1a" = 0, "ap-northeast-1c" = 1 }

  # public.ecr.aws の nginx を、プルスルーキャッシュ（ecr-public/）から dualstack の URI で取る
  poc_image = "${data.aws_caller_identity.current.account_id}.dkr-ecr.ap-northeast-1.on.aws/ecr-public/nginx/nginx:stable-alpine"
}

data "aws_caller_identity" "current" {}

# ---- サブネットと経路 ----

resource "aws_subnet" "poc_ipv6_only" {
  for_each = local.poc_azs

  vpc_id            = module.network.vpc_id
  availability_zone = each.key
  ipv6_cidr_block   = cidrsubnet(module.network.ipv6_cidr_block, 8, each.value)
  ipv6_native       = true

  assign_ipv6_address_on_creation                = true
  enable_resource_name_dns_aaaa_record_on_launch = true

  tags = {
    Name = "${local.poc_name}-ipv6-only-${each.key}"
  }
}

resource "aws_subnet" "poc_dual_stack" {
  for_each = local.poc_azs

  vpc_id            = module.network.vpc_id
  availability_zone = each.key
  cidr_block        = cidrsubnet("10.0.0.0/16", 8, each.value + 100)
  ipv6_cidr_block   = cidrsubnet(module.network.ipv6_cidr_block, 8, each.value + 10)

  assign_ipv6_address_on_creation = true

  tags = {
    Name = "${local.poc_name}-dual-stack-${each.key}"
  }
}

resource "aws_route_table" "poc" {
  vpc_id = module.network.vpc_id

  tags = {
    Name = local.poc_name
  }
}

resource "aws_route" "poc_ipv6_internet" {
  route_table_id              = aws_route_table.poc.id
  destination_ipv6_cidr_block = "::/0"
  egress_only_gateway_id      = module.network.egress_only_internet_gateway_id
}

resource "aws_route_table_association" "poc" {
  for_each = merge(
    { for az, s in aws_subnet.poc_ipv6_only : "ipv6-only-${az}" => s.id },
    { for az, s in aws_subnet.poc_dual_stack : "dual-stack-${az}" => s.id },
  )

  subnet_id      = each.value
  route_table_id = aws_route_table.poc.id
}

# ---- SecurityGroup ----

resource "aws_security_group" "poc_task" {
  name        = "${local.poc_name}-task"
  description = "IPv6 PoC task"
  vpc_id      = module.network.vpc_id
}

resource "aws_security_group" "poc_vpc_link" {
  name        = "${local.poc_name}-vpc-link"
  description = "IPv6 PoC VPC link B"
  vpc_id      = module.network.vpc_id
}

# A と B の VPC リンクから、タスクの 80 番へ
resource "aws_vpc_security_group_ingress_rule" "poc_task_from_vpc_links" {
  for_each = {
    a = module.network.security_group_ids.vpc_link
    b = aws_security_group.poc_vpc_link.id
  }

  security_group_id            = aws_security_group.poc_task.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_a_to_poc_task" {
  security_group_id            = module.network.security_group_ids.vpc_link
  referenced_security_group_id = aws_security_group.poc_task.id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_b_to_poc_task" {
  security_group_id            = aws_security_group.poc_vpc_link.id
  referenced_security_group_id = aws_security_group.poc_task.id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

# ECR と CloudWatch Logs へ（IPv6）
resource "aws_vpc_security_group_egress_rule" "poc_task_https" {
  security_group_id = aws_security_group.poc_task.id
  cidr_ipv6         = "::/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# ---- イメージ ----

resource "aws_ecr_pull_through_cache_rule" "ecr_public" {
  ecr_repository_prefix = "ecr-public"
  upstream_registry_url = "public.ecr.aws"
}

# ---- タスク ----

resource "aws_cloudwatch_log_group" "poc" {
  name              = "/ecs/${local.poc_name}"
  retention_in_days = 1
}

resource "aws_iam_role" "poc_execution" {
  name = "${local.poc_name}-execution"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "poc_execution" {
  role       = aws_iam_role.poc_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# プルスルーキャッシュは、最初に取るときにリポジトリを作り、上流から取り込む
resource "aws_iam_role_policy" "poc_execution_pull_through" {
  role = aws_iam_role.poc_execution.id
  name = "pull-through-cache"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ecr:CreateRepository", "ecr:BatchImportUpstreamImage"]
      Resource = "arn:aws:ecr:ap-northeast-1:${data.aws_caller_identity.current.account_id}:repository/ecr-public/*"
    }]
  })
}

resource "aws_ecs_task_definition" "poc" {
  family                   = local.poc_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.poc_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  # nginx の公式のイメージは、IPv6 が使えれば [::]:80 でも受ける
  container_definitions = jsonencode([{
    name         = "nginx"
    image        = local.poc_image
    essential    = true
    portMappings = [{ containerPort = 80, hostPort = 80, protocol = "tcp" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.poc.name
        awslogs-region        = "ap-northeast-1"
        awslogs-stream-prefix = "nginx"
      }
    }
  }])

  depends_on = [aws_ecr_pull_through_cache_rule.ecr_public]
}

resource "aws_service_discovery_service" "poc" {
  name = "ipv6-poc"

  dns_config {
    namespace_id   = module.quiz_service.service_discovery_namespace_id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "SRV"
      ttl  = 10
    }

    dns_records {
      type = "AAAA"
      ttl  = 10
    }
  }


  # quiz-service と同じく、ECS がタスクの状態を伝える。無いと HealthStatus が UNKNOWN になり、IPv6 と切り分けられない
  health_check_custom_config {
    failure_threshold = 1
  }

  force_destroy = true
}

resource "aws_ecs_service" "poc" {
  name            = "ipv6-poc"
  cluster         = module.quiz_service.cluster_name
  task_definition = aws_ecs_task_definition.poc.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = [for s in aws_subnet.poc_ipv6_only : s.id]
    security_groups  = [aws_security_group.poc_task.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn   = aws_service_discovery_service.poc.arn
    container_name = "nginx"
    container_port = 80
  }

  depends_on = [aws_route.poc_ipv6_internet, aws_route_table_association.poc]
}

# ---- VPC リンク B と試験用の HTTP API ----

resource "aws_apigatewayv2_vpc_link" "poc_b" {
  name               = "${local.poc_name}-b"
  subnet_ids         = [for s in aws_subnet.poc_dual_stack : s.id]
  security_group_ids = [aws_security_group.poc_vpc_link.id]
}

resource "aws_apigatewayv2_api" "poc" {
  name          = local.poc_name
  protocol_type = "HTTP"
}

resource "aws_cloudwatch_log_group" "poc_api" {
  name              = "/aws/apigateway/${local.poc_name}"
  retention_in_days = 1
}

# 届かなかったときの理由（integrationErrorMessage）を残す
resource "aws_apigatewayv2_stage" "poc" {
  api_id      = aws_apigatewayv2_api.poc.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.poc_api.arn
    format = jsonencode({
      requestId        = "$context.requestId"
      routeKey         = "$context.routeKey"
      status           = "$context.status"
      integrationError = "$context.integrationErrorMessage"
      integrationErr2  = "$context.integration.error"
      integrationStat  = "$context.integration.integrationStatus"
      latency          = "$context.integration.latency"
    })
  }
}

resource "aws_apigatewayv2_integration" "poc" {
  for_each = {
    a = module.quiz_service.vpc_link_id
    b = aws_apigatewayv2_vpc_link.poc_b.id
  }

  api_id               = aws_apigatewayv2_api.poc.id
  integration_type     = "HTTP_PROXY"
  integration_method   = "ANY"
  connection_type      = "VPC_LINK"
  connection_id        = each.value
  integration_uri      = aws_service_discovery_service.poc.arn
  timeout_milliseconds = 10000

  # nginx のトップを返させる
  request_parameters = {
    "overwrite:path" = "/"
  }
}

resource "aws_apigatewayv2_route" "poc" {
  for_each = aws_apigatewayv2_integration.poc

  api_id    = aws_apigatewayv2_api.poc.id
  route_key = "GET /${each.key}"
  target    = "integrations/${each.value.id}"
}

output "ipv6_poc_url" {
  value = aws_apigatewayv2_api.poc.api_endpoint
}
