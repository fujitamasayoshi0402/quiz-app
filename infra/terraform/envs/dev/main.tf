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
