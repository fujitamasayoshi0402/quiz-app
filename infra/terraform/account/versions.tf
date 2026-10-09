terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 正確なバージョンは .terraform.lock.hcl が固定する
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-1"

  default_tags {
    tags = {
      Project   = "quiz-app"
      Env       = "shared"
      ManagedBy = "terraform"
    }
  }
}
