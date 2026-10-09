terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
      # CloudFront の証明書は us-east-1 の ACM に置く
      configuration_aliases = [aws.us_east_1]
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.0"
    }
  }
}
