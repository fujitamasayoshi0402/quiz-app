terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-1"

  # 全リソースに付く。Env でコストを環境ごとに分けて見る（コスト配分タグ）
  default_tags {
    tags = {
      Project   = "quiz-app"
      Env       = "dev"
      ManagedBy = "terraform"
    }
  }
}
