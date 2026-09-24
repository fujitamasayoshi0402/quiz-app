# state はバケットを共有し、キーで環境を分ける（ADR-0011）
terraform {
  backend "s3" {
    bucket       = "quiz-app-tfstate-a0139cba"
    key          = "prod/terraform.tfstate"
    region       = "ap-northeast-1"
    use_lockfile = true
    encrypt      = true
  }
}
