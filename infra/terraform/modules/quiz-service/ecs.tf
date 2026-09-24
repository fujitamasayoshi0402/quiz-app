# quiz-service を ECS Fargate で動かす。ローカル（docker compose）と同じイメージを使い、違いは環境変数で渡す。
#
# 同じイメージから 2 つのタスク定義を作る。
#   app     … サービスとして常時動かす。ALB のターゲットになる
#   migrate … デプロイのたびに、サービスを入れ替える前に単発で流す（run-task）。流し終えたら止まる

locals {
  image = "${aws_ecr_repository.quiz_service.repository_url}:${var.image_tag}"

  # IAM 認証（ADR-0014）。AWS Advanced JDBC Wrapper の iam プラグインが、接続のたびにトークンを作ってパスワードに使う。
  # パスワードは空にしておく。ローカル用の既定値（application.yml）が送られないように
  datasource_environment = [
    { name = "SPRING_DATASOURCE_DRIVER_CLASS_NAME", value = "software.amazon.jdbc.Driver" },
    { name = "SPRING_DATASOURCE_PASSWORD", value = "" },
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:aws-wrapper:postgresql://${var.db_endpoint}:${var.db_port}/${var.db_name}?wrapperPlugins=iam&sslmode=require" },
    { name = "SPRING_DATASOURCE_USERNAME", value = "quiz_app" },
  ]
}

resource "aws_ecs_cluster" "this" {
  name = var.name

  # Container Insights はメトリクスごとに課金される。監視は Phase 6 で設計する
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_cloudwatch_log_group" "quiz_service" {
  name              = "/ecs/${var.name}/quiz-service"
  retention_in_days = var.log_retention_days
}

# ---- app ----

resource "aws_ecs_task_definition" "app" {
  family                   = "${var.name}-quiz-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.app.arn

  # Graviton。x86 より 2 割ほど安い。イメージも arm64 でビルドする
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "quiz-service"
    image     = local.image
    essential = true

    portMappings = [{
      containerPort = local.quiz_service_port
      hostPort      = local.quiz_service_port
      protocol      = "tcp"
    }]

    environment = concat(local.datasource_environment, [
      { name = "SPRING_PROFILES_ACTIVE", value = join(",", var.spring_profiles) },
    ])

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.quiz_service.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = "quiz-service"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.service_security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.quiz_service.arn
    container_name   = "quiz-service"
    container_port   = local.quiz_service_port
  }

  # JVM の起動を待つ。短いと、起動中にヘルスチェックで落とされて入れ替えが繰り返される
  health_check_grace_period_seconds = 180

  # 新しいタスクが動くのを確かめてから古いタスクを止める。起動に失敗したら、前のタスク定義に自動で戻す
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  propagate_tags = "SERVICE"

  # ALB にリスナーがない間にサービスを作ると、ターゲットグループを紐付けられない
  depends_on = [aws_lb_listener.http]
}

# ---- migrate ----
# スキーマ所有者（quiz）として接続する。Flyway はアプリの接続設定を引き継ぎ、利用者だけを差し替える

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.name}-quiz-service-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.migrate.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "quiz-service"
    image     = local.image
    essential = true

    environment = concat(local.datasource_environment, [
      { name = "SPRING_FLYWAY_PASSWORD", value = "" },
      { name = "SPRING_FLYWAY_USER", value = "quiz" },
      { name = "SPRING_PROFILES_ACTIVE", value = join(",", concat(var.spring_profiles, ["migrate"])) },
    ])

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.quiz_service.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "migrate"
      }
    }
  }])
}

data "aws_region" "current" {}
