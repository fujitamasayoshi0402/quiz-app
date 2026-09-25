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

  # 使っていない時間は止め、ストレージの料金だけにする。復帰には十数秒かかる（DEV-50 で測る）
  max_capacity             = 2
  seconds_until_auto_pause = 300

  # dev はデモ用のシードしか入らない。消えても migrate で作り直せる
  backup_retention_days = 1
  deletion_protection   = false
  apply_immediately     = true
}

# ドメインの登録時に作られたホストゾーン。環境をまたいで使うため、ここでは管理せず参照だけする
data "aws_route53_zone" "this" {
  name = var.domain_name
}

module "quiz_service" {
  source = "../../modules/quiz-service"

  name = "quiz-app-dev"

  # dev は dev.<ドメイン> の下に置く。web（Amplify）は dev.<ドメイン>、API は api.dev.<ドメイン>
  api_domain_name = "api.dev.${var.domain_name}"
  hosted_zone_id  = data.aws_route53_zone.this.zone_id

  vpc_id                    = module.network.vpc_id
  public_subnet_ids         = module.network.public_subnet_ids
  alb_security_group_id     = module.network.security_group_ids.alb
  service_security_group_id = module.network.security_group_ids.quiz_service

  db_endpoint      = module.database.cluster_endpoint
  db_port          = module.database.port
  db_name          = module.database.database_name
  iam_db_user_arns = module.database.iam_db_user_arns

  image_tag = var.quiz_service_image_tag

  # 0.25 vCPU では Spring Boot の起動に時間がかかり、デプロイのたびに待たされる
  cpu           = 512
  memory        = 1024
  desired_count = 1

  # dev はデモに使う。スタブ認証とシードを有効にする（prod / stg では起動に失敗する）
  spring_profiles = ["dev"]

  log_retention_days  = 14
  force_delete_images = true
}

module "web" {
  source = "../../modules/web"

  name           = "quiz-app-dev-web"
  repository_url = "https://github.com/fujitamasayoshi0402/quiz-app"
  branch_name    = "develop"

  domain_name      = var.domain_name
  subdomain_prefix = "dev"

  api_origin           = module.quiz_service.api_url
  origin_verify_secret = module.quiz_service.origin_verify_secret

  github_access_token = var.github_access_token
  basic_auth_username = "demo"
}

# GitHub Actions が dev へのデプロイに使うロール。develop にだけ使わせる（GitHub の Environment dev で絞る）
module "deploy_role" {
  source = "../../modules/deploy-role"

  name               = "quiz-app-dev"
  github_repository  = "fujitamasayoshi0402/quiz-app"
  github_environment = "dev"

  ecr_repository_arn       = module.quiz_service.ecr_repository_arn
  ecs_cluster_arn          = module.quiz_service.cluster_arn
  ecs_service_arn          = module.quiz_service.service_arn
  task_definition_families = module.quiz_service.task_definition_families
  task_role_arns           = module.quiz_service.task_role_arns

  amplify_branch_arn = module.web.branch_arn
}
