# bootstrap 自身の state も、ここで作ったバケットに置く。
#
# 最初の 1 回だけは、このファイルが無い状態（ローカル state）でバケットを作り、
# そのあとこのファイルを置いて `terraform init -migrate-state` で移した。
# 作り直す手順は docs/development-guidelines.md の「インフラ（Terraform）」を参照。
terraform {
  backend "s3" {
    bucket       = "quiz-app-tfstate-a0139cba"
    key          = "bootstrap/terraform.tfstate"
    region       = "ap-northeast-1"
    use_lockfile = true
    encrypt      = true
  }
}
