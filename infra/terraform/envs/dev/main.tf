# dev 環境のルートモジュール。リソースは modules/ の部品を組み合わせて足していく。
#
# dev と prod は別々のルートモジュールにしている（workspace は使わない）。
# 環境ごとの差（台数・サイズ・夜間停止の有無）をコードの差分として読めるようにするため。

module "network" {
  source = "../../modules/network"

  name       = "quiz-app-dev"
  cidr_block = "10.0.0.0/16"
  azs        = ["ap-northeast-1a", "ap-northeast-1c"]

  alb_ingress_cidrs = var.alb_ingress_cidrs
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

module "quiz_service" {
  source = "../../modules/quiz-service"

  name = "quiz-app-dev"

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
