# 図を配る CloudFront。署名付き URL だけを受け付ける。
#
# web（Amplify）とは別のディストリビューションにする。Amplify の CDN は Amplify が管理しており、
# 非公開の S3 と署名を組み込めない。図のドメインをアプリと分けることで、SVG をアプリのオリジンで開かせない

resource "aws_cloudfront_origin_access_control" "this" {
  name                              = "${var.name}-figures"
  description                       = "CloudFront reads figures from the private bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# クエリ文字列（署名）も Cookie もキャッシュのキーに含めない。同じ図は、署名が違っても 1 つのキャッシュを使う。
# 期間は S3 のオブジェクトの Cache-Control に従う（図は変えないので 1 年）
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# SVG はスクリプトを含められる。直接開かれても、スクリプトを動かさず、外へ通信させない。
# <img> で読むときは、もともとスクリプトは動かない
resource "aws_cloudfront_response_headers_policy" "figures" {
  name    = "${var.name}-figures"
  comment = "Keeps scripts in SVG from running"

  security_headers_config {
    # draw.io の SVG は、見た目をインラインの style で持ち、画像やフォントを data: で埋め込むことがある
    content_security_policy {
      content_security_policy = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; sandbox"
      override                = true
    }

    # image/svg+xml 以外として解釈させない
    content_type_options {
      override = true
    }

    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      override                   = true
    }

    referrer_policy {
      referrer_policy = "no-referrer"
      override        = true
    }
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled         = true
  comment         = "${var.name} figures (ADR-0017)"
  aliases         = [var.domain_name]
  is_ipv6_enabled = true
  http_version    = "http2and3"

  # 日本を含む価格帯のうち、最も安いもの。PriceClass_100 は北米と欧州のエッジだけで、日本から遠い
  price_class = "PriceClass_200"

  origin {
    origin_id                = "s3"
    domain_name              = aws_s3_bucket.this.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.this.id
  }

  default_cache_behavior {
    target_origin_id = "s3"

    # HTTP は受けない。署名付き URL が平文で流れないようにする
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.figures.id

    # キーグループの鍵で署名された URL だけを通す。署名のない要求と、期限の切れた要求は 403
    trusted_key_groups = [aws_cloudfront_key_group.this.id]
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 独自ドメインにするのは、TLS の最低のバージョンを選ぶため。既定のドメイン（*.cloudfront.net）では選べない
  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.this.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ---- ドメイン ----

# CloudFront の証明書は us-east-1 に置く。ACM が DNS 検証で自動更新する
resource "aws_acm_certificate" "this" {
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = {
    for option in aws_acm_certificate.this.domain_validation_options : option.domain_name => option
  }

  zone_id = var.hosted_zone_id
  name    = each.value.resource_record_name
  type    = each.value.resource_record_type
  records = [each.value.resource_record_value]
  ttl     = 300
}

resource "aws_acm_certificate_validation" "this" {
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

resource "aws_route53_record" "a" {
  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "aaaa" {
  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}
