terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.1"
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

# CloudFront の証明書は us-east-1 の ACM に置く（modules/figures）
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project   = "quiz-app"
      Env       = "dev"
      ManagedBy = "terraform"
    }
  }
}
