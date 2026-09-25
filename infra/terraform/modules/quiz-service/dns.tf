# API のドメイン名と証明書。ALB の DNS 名（*.elb.amazonaws.com）では証明書が合わないため、独自ドメインで公開する。
#
# ホストゾーンはドメインの登録時に作られ、環境をまたいで使う。ここでは作らず、受け取った ID にレコードを足すだけにする

# ALB と同じリージョンに置く。証明書は ACM が自動で更新する（DNS 検証のレコードを消さない限り）
resource "aws_acm_certificate" "api" {
  domain_name       = var.api_domain_name
  validation_method = "DNS"

  # 作り直すときに、ALB が証明書を持たない時間を作らない
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_validation" {
  for_each = {
    for option in aws_acm_certificate.api.domain_validation_options : option.domain_name => option
  }

  zone_id = var.hosted_zone_id
  name    = each.value.resource_record_name
  type    = each.value.resource_record_type
  records = [each.value.resource_record_value]
  ttl     = 300
}

# 検証が済むまで待つ。済む前の証明書はリスナーに付けられない
resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for record in aws_route53_record.api_validation : record.fqdn]
}

resource "aws_route53_record" "api" {
  zone_id = var.hosted_zone_id
  name    = var.api_domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = false
  }
}
