terraform {
  backend "s3" {
    bucket       = "quiz-app-tfstate-a0139cba"
    key          = "account/terraform.tfstate"
    region       = "ap-northeast-1"
    use_lockfile = true
    encrypt      = true
  }
}
