# prod 環境のルートモジュール。リソースは modules/ の部品を組み合わせて足していく。
#
# dev と prod は別々のルートモジュールにしている（workspace は使わない）。
# 環境ごとの差（台数・サイズ・夜間停止の有無）をコードの差分として読めるようにするため。
#
# dev との違いは ADR-0024 の「dev と prod の違い」にまとめてある。値の横のコメントは、dev と違う理由
#
# **apply は main から行う。** prod には、リリースしたものと同じ形だけを流す（ADR-0024）

module "network" {
  source = "../../modules/network"

  name = "quiz-app-prod"
  # dev（10.0.0.0/16）と重ねない。つなぐ予定はないが、重なっていると後からつなげない
  cidr_block = "10.1.0.0/16"
  azs        = ["ap-northeast-1a", "ap-northeast-1c"]
}

module "database" {
  source = "../../modules/database"

  name              = "quiz-app-prod"
  engine_version    = "16.14"
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.security_group_ids.db

  # min 0 ACU のまま（ADR-0024 の C-1）。止まるまでは 60 分。見に来た人が読んでいる間に止めない
  max_capacity             = 2
  seconds_until_auto_pause = 3600

  # 気づくのが遅れても戻せるように 7 日。DB の大きさまでのバックアップは無料。
  # 削除保護を付け、消すときは最終スナップショットを取る（modules/database）。誤った apply や destroy で消さない
  backup_retention_days = 7
  deletion_protection   = true
  # 設定の変更は、次のメンテナンスの時間帯に当てる。apply の直後に再起動させない
  apply_immediately = false
}

locals {
  # テナントの Slack の Webhook の URL を置く SSM のパラメータの頭（ADR-0022）。quiz-service が書き、notification-service が読む
  slack_webhook_parameter_prefix = "/quiz-app/prod"

  # 運用の文書。prod はリリースしたもの（main）を指す
  docs_url    = "https://github.com/fujitamasayoshi0402/quiz-app/blob/main/docs"
  runbook_url = "${local.docs_url}/runbook.md"

  # prod は quiz.<ドメイン> の下に置く。apex には、このアプリ以外の既存のレコードがある（触らない）
  web_domain = "quiz.${var.domain_name}"
}

# ドメインの登録時に作られたホストゾーン。環境をまたいで使うため、ここでは管理せず参照だけする
data "aws_route53_zone" "this" {
  name = var.domain_name
}

# クイズのイベントを流すバス（ADR-0022）。quiz-service が送り、notification-service が受ける
module "events" {
  source = "../../modules/events"

  name = "quiz-app-prod"
}

# アラームの送り先。各モジュールのアラームがここへ送り、メールで知らせる（DEV-108）
module "alarms" {
  source = "../../modules/alarms"

  name  = "quiz-app-prod"
  email = var.alarm_email
}

# 運用で見るものを 1 画面にまとめる（DEV-109）
module "dashboard" {
  source = "../../modules/dashboard"

  name                        = "quiz-app-prod"
  quiz_service                = module.quiz_service.monitoring
  notification                = module.notification_service.monitoring
  database_cluster_identifier = module.database.cluster_identifier
  guide_url                   = "${local.docs_url}/development-guidelines.md#ダッシュボード"
}

# クイズのイベントを受けて、テナントが設定した Slack に知らせる（ADR-0022）
module "notification_service" {
  source = "../../modules/notification-service"

  name                     = "quiz-app-prod"
  event_bus_name           = module.events.bus_name
  webhook_parameter_prefix = local.slack_webhook_parameter_prefix
  web_base_url             = "https://${local.web_domain}"

  # 関数を作るときにだけ読む。先に ./gradlew :services:notification-service:buildZip で作っておく
  package_path = "${path.root}/../../../../services/notification-service/build/distributions/notification-service.zip"

  alarm_topic_arn = module.alarms.topic_arn
  runbook_url     = local.runbook_url

  # 障害のあとに遡れるように 30 日
  log_retention_days = 30
}

module "quiz_service" {
  source = "../../modules/quiz-service"

  name = "quiz-app-prod"

  api_domain_name = "api.${local.web_domain}"
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

  db_endpoint      = module.database.cluster_endpoint
  db_port          = module.database.port
  db_name          = module.database.database_name
  iam_db_user_arns = module.database.iam_db_user_arns

  image_tag = var.quiz_service_image_tag

  cpu    = 512
  memory = 1024
  # ステートレスで、落ちても ECS が起動し直す。2 つにすると費用が倍になる（ADR-0024）
  desired_count = 1

  # 夜間も止めない。見に来る時間を選べない（ADR-0024）
  nightly_stop = null

  # デモのシードは入れない（SeedDataGuard が止める）。スモークテストのテナントだけを入れる（application-smoke.yml）
  spring_profiles = ["prod", "smoke"]

  alarm_topic_arn = module.alarms.topic_arn
  runbook_url     = local.runbook_url

  log_retention_days  = 30
  force_delete_images = false

  # 公開までは量が少ないため、すべて記録する。X-Ray は月 10 万件まで無料（dev と合わせて）。量が増えたら下げる
  tracing_sampling_probability = 1
}

# 解説図の置き場所と配信（ADR-0017）。図は figures.quiz.<ドメイン> から配る
module "figures" {
  source = "../../modules/figures"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name           = "quiz-app-prod"
  domain_name    = "figures.${local.web_domain}"
  hosted_zone_id = data.aws_route53_zone.this.zone_id

  upload_allowed_origins = ["https://${local.web_domain}"]

  # 利用者の図がある。バケットに図が残っていると消せない
  force_destroy = false

  # Aurora のバックアップ（7 日）より長く残す（DEV-117）
  deleted_retention_days = 14
}

# 利用者の認証（ADR-0016）。dev とは別の User Pool にし、利用者を混ぜない
module "auth" {
  source = "../../modules/auth"

  name = "quiz-app-prod"

  # ローカルの web からは prod にログインさせない
  app_origins = ["https://${local.web_domain}"]

  # スモークテスト（tests/api）の利用者。シード（db/smoke）が同じアドレスでスモークテストのテナントの管理者を用意している
  smoke_user_email = "smoke@example.com"

  # デモのアカウント、E2E、負荷試験の利用者は作らない。公開のときのデモは Phase 7 で用意する（ADR-0024）

  deletion_protection = true
}

module "web" {
  source = "../../modules/web"

  name           = "quiz-app-prod-web"
  repository_url = "https://github.com/fujitamasayoshi0402/quiz-app"
  branch_name    = "main"

  domain_name      = var.domain_name
  subdomain_prefix = "quiz"

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

  log_retention_days = 30
}
