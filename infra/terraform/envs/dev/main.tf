# dev 環境のルートモジュール。リソースは modules/ の部品を組み合わせて足していく。
#
# dev と prod は別々のルートモジュールにしている（workspace は使わない）。
# 環境ごとの差（台数・サイズ・夜間停止の有無）をコードの差分として読めるようにするため。

module "network" {
  source = "../../modules/network"

  name       = "quiz-app-dev"
  cidr_block = "10.0.0.0/16"
  azs        = ["ap-northeast-1a", "ap-northeast-1c"]
}

module "database" {
  source = "../../modules/database"

  name              = "quiz-app-dev"
  engine_version    = "16.14"
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.security_group_ids.db

  # 使っていない時間は止め、ストレージの料金だけにする。復帰には十数秒かかる（DEV-50 で測る）。
  # 止まるまでは 30 分おく（DEV-89）。5 分だと、画面を読んだり解説を書いたりしている間に止まり、次の操作のたびに復帰を待つ
  max_capacity             = 2
  seconds_until_auto_pause = 1800

  # dev はデモ用のシードしか入らない。消えても migrate で作り直せる
  backup_retention_days = 1
  deletion_protection   = false
  apply_immediately     = true
}

# バックアップから戻したクラスタ（DEV-117）。ふだんは置かない。手順は Runbook の「Aurora のデータを戻す」。
# 戻す元は上のクラスタのバックアップ。設定は上と揃える
module "database_restore" {
  source = "../../modules/database"
  count  = var.database_restore == null ? 0 : 1

  name              = "quiz-app-dev-restore"
  engine_version    = module.database.engine_version
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.security_group_ids.db

  max_capacity             = 2
  seconds_until_auto_pause = 1800

  backup_retention_days = 1
  deletion_protection   = false
  apply_immediately     = true

  restore_from = {
    source_cluster_identifier = module.database.cluster_identifier
    restore_to_time           = var.database_restore.restore_to_time
  }
}

locals {
  # アプリ（quiz-service とマイグレーション）がつなぐクラスタ。戻したクラスタに切り替えると、接続先と IAM 認証の許可が替わる
  app_database = try(var.database_restore.use_for_app, false) ? module.database_restore[0] : module.database
}

locals {
  # テナントの Slack の Webhook の URL を置く SSM のパラメータの頭（ADR-0022）。quiz-service が書き、notification-service が読む
  slack_webhook_parameter_prefix = "/quiz-app/dev"

  # 運用の文書。アラームの説明とダッシュボードから、ここへ案内する（公開リポジトリ）
  docs_url    = "https://github.com/fujitamasayoshi0402/quiz-app/blob/develop/docs"
  runbook_url = "${local.docs_url}/runbook.md"
}

# ドメインの登録時に作られたホストゾーン。環境をまたいで使うため、ここでは管理せず参照だけする
data "aws_route53_zone" "this" {
  name = var.domain_name
}

# クイズのイベントを流すバス（ADR-0022）。quiz-service が送り、notification-service が受ける
module "events" {
  source = "../../modules/events"

  name = "quiz-app-dev"
}

# アラームの送り先。各モジュールのアラームがここへ送り、メールで知らせる（DEV-108）
module "alarms" {
  source = "../../modules/alarms"

  name  = "quiz-app-dev"
  email = var.alarm_email
}

# 運用で見るものを 1 画面にまとめる（DEV-109）
module "dashboard" {
  source = "../../modules/dashboard"

  name                        = "quiz-app-dev"
  quiz_service                = module.quiz_service.monitoring
  notification                = module.notification_service.monitoring
  database_cluster_identifier = local.app_database.cluster_identifier
  guide_url                   = "${local.docs_url}/development-guidelines.md#ダッシュボード"
}

# クイズのイベントを受けて、テナントが設定した Slack に知らせる（ADR-0022）
module "notification_service" {
  source = "../../modules/notification-service"

  name                     = "quiz-app-dev"
  event_bus_name           = module.events.bus_name
  webhook_parameter_prefix = local.slack_webhook_parameter_prefix
  web_base_url             = "https://dev.${var.domain_name}"

  # 関数を作るときにだけ読む。先に ./gradlew :services:notification-service:buildZip で作っておく
  package_path = "${path.root}/../../../../services/notification-service/build/distributions/notification-service.zip"

  alarm_topic_arn = module.alarms.topic_arn
  runbook_url     = local.runbook_url

  log_retention_days = 14
}

module "quiz_service" {
  source = "../../modules/quiz-service"

  name = "quiz-app-dev"

  # dev は dev.<ドメイン> の下に置く。web（Amplify）は dev.<ドメイン>、API は api.dev.<ドメイン>
  api_domain_name = "api.dev.${var.domain_name}"
  hosted_zone_id  = data.aws_route53_zone.this.zone_id

  vpc_id                    = module.network.vpc_id
  public_subnet_ids         = module.network.public_subnet_ids
  service_security_group_id = module.network.security_group_ids.quiz_service

  vpc_link_subnet_ids        = module.network.private_subnet_ids
  vpc_link_security_group_id = module.network.security_group_ids.vpc_link

  auth_issuer    = module.auth.issuer
  auth_client_id = module.auth.client_id

  figures = {
    bucket_name               = module.figures.bucket_name
    bucket_arn                = module.figures.bucket_arn
    base_url                  = module.figures.base_url
    key_pair_id               = module.figures.key_pair_id
    private_key_parameter_arn = module.figures.private_key_parameter_arn
  }

  slack_webhook_parameter_prefix = local.slack_webhook_parameter_prefix

  event_bus = {
    name = module.events.bus_name
    arn  = module.events.bus_arn
  }

  db_endpoint      = local.app_database.cluster_endpoint
  db_port          = local.app_database.port
  db_name          = local.app_database.database_name
  iam_db_user_arns = local.app_database.iam_db_user_arns

  image_tag = var.quiz_service_image_tag

  # 0.25 vCPU では Spring Boot の起動に時間がかかり、デプロイのたびに待たされる
  cpu           = 512
  memory        = 1024
  desired_count = 1

  # ふだんは既定（20 件/秒）。負荷試験の間だけ -var で上げる
  throttling = var.api_throttling

  # 使わない深夜は止める。Aurora のストレージなどは、止めている間も課金が続く
  nightly_stop = {
    stop     = "cron(0 2 * * ? *)"
    start    = "cron(0 8 * * ? *)"
    timezone = "Asia/Tokyo"
  }

  # dev はデモに使う。スタブ認証とシードを有効にする（prod / stg では起動に失敗する）
  spring_profiles = ["dev"]

  alarm_topic_arn = module.alarms.topic_arn
  runbook_url     = local.runbook_url

  log_retention_days  = 14
  force_delete_images = true
}

# 解説図の置き場所と配信（ADR-0017）。web は dev.<ドメイン>、図は figures.dev.<ドメイン> から配る
module "figures" {
  source = "../../modules/figures"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name           = "quiz-app-dev"
  domain_name    = "figures.dev.${var.domain_name}"
  hosted_zone_id = data.aws_route53_zone.this.zone_id

  # ローカルの web も dev のバケットには上げない（LocalStack を使う）。ここに載せるのは dev の画面だけ
  upload_allowed_origins = ["https://dev.${var.domain_name}"]

  # dev の図はデモとスモークテストのもの。環境ごと作り直せるようにする
  force_destroy = true

  # Aurora のバックアップ（1 日）より長く残す。アーカイブ（7 日）と揃える
  deleted_retention_days = 7
}

# 利用者の認証（ADR-0016）。ローカルの web も、この User Pool でログインする
module "auth" {
  source = "../../modules/auth"

  name = "quiz-app-dev"

  # Amplify の既定のドメインは入れない。web のアプリの属性で、web のアプリがこのモジュールの値を読むため循環する
  app_origins = [
    "https://dev.${var.domain_name}",
    "http://localhost:3000",
  ]

  # スモークテスト（tests/api）の利用者。シードが同じアドレスでスモークテストのテナントの管理者を用意している
  smoke_user_email = "smoke@example.com"

  # 見に来た人が試すための共有のデモのアカウント。シードが同じアドレスで、デモのテナントの一般ユーザーを用意している
  demo_user_email = "demo@example.com"

  # E2E テスト（tests/e2e）の利用者。役割はテストがローカルの DB で割り当てる（管理者 / 一般ユーザー / 未所属 / 招待される人）
  e2e_user_emails = [
    "e2e-admin@example.com",
    "e2e-member@example.com",
    "e2e-outsider@example.com",
    "e2e-invitee@example.com",
  ]

  # 負荷試験（tests/load）の利用者。シードが同じアドレスで、負荷試験のテナントの管理者 1 人と一般ユーザー 50 人を用意している
  load_user_emails = concat(
    ["load-admin@example.com"],
    [for n in range(1, 51) : format("load-%02d@example.com", n)],
  )

  deletion_protection = false
}

module "web" {
  source = "../../modules/web"

  name           = "quiz-app-dev-web"
  repository_url = "https://github.com/fujitamasayoshi0402/quiz-app"
  branch_name    = "develop"

  domain_name      = var.domain_name
  subdomain_prefix = "dev"

  api_origin = module.quiz_service.api_url

  content_security_policy_origins = {
    images  = [module.figures.base_url]
    connect = [module.figures.upload_origin]
  }

  auth = {
    issuer        = module.auth.issuer
    client_id     = module.auth.client_id
    client_secret = module.auth.client_secret
  }

  github_access_token = var.github_access_token

  log_retention_days = 14
}

# GitHub Actions が dev へのデプロイに使うロール。develop にだけ使わせる（GitHub の Environment dev で絞る）
module "deploy_role" {
  source = "../../modules/deploy-role"

  name                  = "quiz-app-dev"
  github_subject_prefix = "repo:fujitamasayoshi0402@62087486/quiz-app@1379474045"
  github_environment    = "dev"

  ecr_repository_arn       = module.quiz_service.ecr_repository_arn
  ecs_cluster_arn          = module.quiz_service.cluster_arn
  ecs_service_arn          = module.quiz_service.service_arn
  task_definition_families = module.quiz_service.task_definition_families
  task_role_arns           = module.quiz_service.task_role_arns

  amplify_branch_arn = module.web.branch_arn

  notification_function_arn = module.notification_service.function_arn
}
