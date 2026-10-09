# quiz-service を ECS Fargate で動かす。ローカル（docker compose）と同じイメージを使い、違いは環境変数で渡す。
#
# 同じイメージから 2 つのタスク定義を作る。
#   app     … サービスとして常時動かす。API Gateway が Cloud Map で見つけて要求を送る（api.tf）
#   migrate … デプロイのたびに、サービスを入れ替える前に単発で流す（run-task）。流し終えたら止まる
#
# ここで決めるのはタスク定義の形（環境変数、ロール、CPU など）まで。
# デプロイは、最新のリビジョンのイメージだけを差し替えて登録し直す（ADR-0015）。
# 形を変えて apply しても、動いているタスクは替わらない。次のデプロイで反映される

locals {
  image             = "${aws_ecr_repository.quiz_service.repository_url}:${var.image_tag}"
  quiz_service_port = 8080

  # IAM 認証（ADR-0014）。AWS Advanced JDBC Wrapper の iam プラグインが、接続のたびにトークンを作ってパスワードに使う。
  # パスワードは空にしておく。ローカル用の既定値（application.yml）が送られないように。
  #
  # wrapperDialect=pg で、Aurora 用の機能を使わない。Aurora と判定させると、ラッパーがクラスタの構成を見張る接続を
  # プールとは別に張り、最後の利用から 15 分ほど 30 秒ごとに問い合わせ続ける。その間 Aurora が一時停止しない（DEV-50）。
  # フェイルオーバーのプラグインは使っていないため、構成の情報は要らない
  datasource_environment = [
    { name = "SPRING_DATASOURCE_DRIVER_CLASS_NAME", value = "software.amazon.jdbc.Driver" },
    { name = "SPRING_DATASOURCE_PASSWORD", value = "" },
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:aws-wrapper:postgresql://${var.db_endpoint}:${var.db_port}/${var.db_name}?wrapperPlugins=iam&wrapperDialect=pg&sslmode=require" },
    { name = "SPRING_DATASOURCE_USERNAME", value = "quiz_app" },
  ]

  # アクセストークンの発行者と、受け取る web のクライアント（ADR-0016）。無いとアプリが起動しない（AuthProperties）。
  # マイグレーションのタスクも同じアプリとして起動するため、両方に渡す
  auth_environment = [
    { name = "AUTH_CLIENT_ID", value = var.auth_client_id },
    { name = "AUTH_ISSUER", value = var.auth_issuer },
  ]

  # 解説図（ADR-0017）。アプリのタスクにだけ渡す。マイグレーションは図に触れない。
  # S3 の接続先を空にして、ローカル用の既定値（LocalStack）を使わせない。空なら AWS の S3 に接続する
  figures_environment = [
    { name = "FIGURES_BUCKET", value = var.figures.bucket_name },
    { name = "FIGURES_CLOUDFRONT_KEY_PAIR_ID", value = var.figures.key_pair_id },
    { name = "FIGURES_CLOUDFRONT_URL", value = var.figures.base_url },
    { name = "FIGURES_S3_ENDPOINT", value = "" },
  ]

  # テナントの Slack の Webhook の URL の置き場所（ADR-0022）。アプリのタスクにだけ渡す。
  # SSM の接続先を空にして、ローカル用の既定値（LocalStack）を使わせない
  notifications_environment = [
    { name = "NOTIFICATIONS_PARAMETER_PREFIX", value = var.slack_webhook_parameter_prefix },
    { name = "NOTIFICATIONS_SSM_ENDPOINT", value = "" },
  ]

  # クイズのイベントを送るバス（ADR-0022）。アプリのタスクにだけ渡す。
  # 接続先を空にして、ローカル用の既定値（LocalStack）を使わせない
  # ログを JSON（ECS 形式）で出す。Logs Insights で項目として絞り込め、例外のスタックトレースも 1 件のログに収まる。
  # ローカルは平文のまま（開発ガイドライン「ログ」）。マイグレーションのタスクも同じ形で出す
  logging_environment = [
    { name = "LOGGING_STRUCTURED_FORMAT_CONSOLE", value = "ecs" },
  ]

  # 分散トレース（ADR-0026）。アプリは同じタスクのコレクタへ OTLP で送り、コレクタが X-Ray へ送る。
  # タスクの中のコンテナは、ネットワークを共有する（awsvpc）。コレクタは localhost でだけ受ける。
  # マイグレーションのタスクには渡さない。送り先が無ければ、アプリはトレースを送らない
  tracing_environment = [
    { name = "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT", value = "http://localhost:4318/v1/traces" },
    { name = "TRACING_SAMPLING_PROBABILITY", value = tostring(var.tracing_sampling_probability) },
  ]

  # ADOT コレクタ。版はダイジェストで固定する（タグは付け替えられる）
  otel_collector_image = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0@sha256:7968fb60db6a2390a47ba6a2df029745638486e285c9b2487da1b722d0855a3e"

  # コレクタの設定。受けたトレースを、まとめて X-Ray へ送るだけ。メトリクスとログは扱わない。
  # memory_limiter は、コンテナのメモリの上限（128 MB）に届く前に受け付けを止める。アプリには影響しない（送れずに捨てる）
  otel_collector_config = yamlencode({
    extensions = { health_check = { endpoint = "localhost:13133" } }
    receivers  = { otlp = { protocols = { http = { endpoint = "localhost:4318" } } } }
    processors = {
      memory_limiter = { check_interval = "1s", limit_mib = 100, spike_limit_mib = 20 }
      batch          = { timeout = "1s" }
    }
    exporters = { awsxray = { region = data.aws_region.current.region } }
    service = {
      extensions = ["health_check"]
      pipelines = {
        traces = { receivers = ["otlp"], processors = ["memory_limiter", "batch"], exporters = ["awsxray"] }
      }
    }
  })

  events_environment = [
    { name = "EVENTS_BUS_NAME", value = var.event_bus.name },
    { name = "EVENTS_ENDPOINT", value = "" },
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

    # コレクタが起動してから起動する。受け手がいないうちのトレースを落とさない。
    # 健全になるまでは待たない。コレクタが立ち上がらなくても、アプリは動く
    dependsOn = [{ containerName = "otel-collector", condition = "START" }]

    portMappings = [{
      containerPort = local.quiz_service_port
      hostPort      = local.quiz_service_port
      protocol      = "tcp"
    }]

    # ECS がタスクの状態を決め、Cloud Map に伝える（api.tf）。通るまで API Gateway は要求を送らない。
    # デプロイの入れ替えとサーキットブレーカーも、この結果で判断する。
    # アクチュエータは DB に問い合わせない（application.yml）。叩き続けても Aurora の一時停止を妨げない。
    # startPeriod の間の失敗は数えない（通れば、その時点で HEALTHY になる）。JVM の起動を待つ。
    # 短いと、起動の途中でヘルスチェックに落とされ、入れ替えが繰り返される
    healthCheck = {
      command     = ["CMD-SHELL", "curl -sf http://localhost:${local.quiz_service_port}/actuator/health || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 180
    }

    environment = concat(local.auth_environment, local.datasource_environment, local.logging_environment, local.figures_environment, local.notifications_environment, local.events_environment, local.tracing_environment, [
      { name = "SPRING_PROFILES_ACTIVE", value = join(",", var.spring_profiles) },
    ])

    # 署名の秘密鍵は、起動のときに ECS が SSM から読んで環境変数に入れる。タスク定義には値が残らない
    secrets = [
      { name = "FIGURES_CLOUDFRONT_PRIVATE_KEY", valueFrom = var.figures.private_key_parameter_arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.quiz_service.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "app"
      }
    }
    }, {
    # 分散トレースのコレクタ（ADR-0026）。止まってもタスクは止めない（essential = false）。トレースが欠けるだけ
    name      = "otel-collector"
    image     = local.otel_collector_image
    essential = false
    memory    = 128

    environment = [
      { name = "AOT_CONFIG_CONTENT", value = local.otel_collector_config },
    ]

    healthCheck = {
      command     = ["/healthcheck"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 10
    }

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.quiz_service.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "otel-collector"
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

  # タスクの IP とポートを Cloud Map に登録する。API Gateway はここから送り先を引く（api.tf）
  service_registries {
    registry_arn   = aws_service_discovery_service.quiz_service.arn
    container_name = "quiz-service"
    container_port = local.quiz_service_port
  }

  # 新しいタスクが動くのを確かめてから古いタスクを止める。起動に失敗したら、前のタスク定義に自動で戻す
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  propagate_tags = "SERVICE"

  # どのリビジョンを動かすかは、デプロイ（GitHub Actions）が決める（ADR-0015）。
  # Terraform が登録したリビジョンは、次のデプロイで形の元として使われ、イメージだけが差し替わる。
  # タスクの数は、夜間の停止（schedule.tf）が変える。比べると、夜に apply したときに起動してしまう
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
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

    environment = concat(local.auth_environment, local.datasource_environment, local.logging_environment, [
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
