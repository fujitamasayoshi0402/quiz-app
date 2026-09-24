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
