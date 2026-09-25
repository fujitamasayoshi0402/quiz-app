# quiz-service を公開する ALB。web は Amplify Hosting から、この ALB を呼ぶ（ADR-0012）。
#
# スタブ認証の間は、`X-User-Id` を付けるだけで誰にでもなりすませる。
# そこで **web の proxy だけが知る秘密のヘッダを ALB で確かめ、持たない要求はアプリに届く前に拒否する。**
# Phase 3 でアプリが JWT を検証するようになったら、この確認は外せる。

locals {
  quiz_service_port  = 8080
  origin_header_name = "X-Origin-Verify"
}

resource "aws_lb" "this" {
  name               = var.name
  load_balancer_type = "application"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [var.alb_security_group_id]

  # HTTP の仕様に合わないヘッダ名を落とす。ヘッダを使ったすり抜けの余地を減らす
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "quiz_service" {
  name        = "${var.name}-quiz-service"
  port        = local.quiz_service_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  # 既定の 300 秒では、デプロイのたびに古いタスクが止まるまで待たされる
  deregistration_delay = 30

  # DB には問い合わせない（application.yml）。ALB が定期的に叩いても Aurora の一時停止を妨げない
  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# HTTPS（DEV-47）が入るまでは HTTP。受信する相手は SecurityGroup で絞る（modules/network）
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  # 秘密のヘッダを持たない要求は、すべてここで止まる
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "origin_verified" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    http_header {
      http_header_name = local.origin_header_name
      values           = [random_password.origin_verify.result]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.quiz_service.arn
  }
}

# ---- 秘密のヘッダの値 ----
# web（Amplify、DEV-53）はこのシークレットから値を読んで付ける。
#
# 値は Terraform の state にも入る。ALB のルールの条件そのものが値を持つため、避けられない。
# 入れ替えるときは `terraform apply -replace=module.quiz_service.random_password.origin_verify` のあと、web をビルドし直す

resource "random_password" "origin_verify" {
  length = 40

  # ALB のヘッダの条件では * と ? がワイルドカードになる。記号を混ぜると、意図しない値まで一致しうる
  special = false
}

resource "aws_secretsmanager_secret" "origin_verify" {
  name        = "${var.name}/origin-verify-header"
  description = "Value of the ${local.origin_header_name} header that the ALB requires"

  # 作り直せる値なので、削除の猶予期間を置かない。置くと、同じ名前で作り直せない期間ができる
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "origin_verify" {
  secret_id     = aws_secretsmanager_secret.origin_verify.id
  secret_string = random_password.origin_verify.result
}
