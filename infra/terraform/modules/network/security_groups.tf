# 通信の許可。外から届く入口は ALB だけにし、あとは 1 段ずつ隣へ渡す。
#
#   インターネット → ALB → web → quiz-service → Aurora
#
# ECS のタスクはパブリック IP を持つ（ADR-0013）。**タスクへの受信は SG の参照だけで許し、CIDR では開けない。**
# CIDR で開けると、ALB を通らずタスクへ直接届く。
#
# quiz-service は ALB に載せず、web からだけ届くようにする（ADR-0012）。
# スタブ認証の間、`X-User-Id` を付けてよいのは web の proxy だけのため。

locals {
  web_port          = 3000
  quiz_service_port = 8080
  db_port           = 5432

  # ECR からのイメージの取得、CloudWatch Logs、Secrets Manager。いずれも公開エンドポイントへ HTTPS で出る
  anywhere = "0.0.0.0/0"
}

# SG の description は英数字しか使えず、変えると作り直しになる

# ---- ALB ----

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "ALB in front of web"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-alb"
  }
}

# 受信はまだ開けない。スタブ認証の間は、誰でも管理者になりすませる。
# 誰から受けるかは、dev へのアクセス制限の課題（DEV-46）で決める

resource "aws_vpc_security_group_egress_rule" "alb_to_web" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.web.id
  ip_protocol                  = "tcp"
  from_port                    = local.web_port
  to_port                      = local.web_port
  description                  = "Forward to web"
}

# ---- web ----

resource "aws_security_group" "web" {
  name        = "${var.name}-web"
  description = "web (Next.js) tasks"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-web"
  }
}

resource "aws_vpc_security_group_ingress_rule" "web_from_alb" {
  security_group_id            = aws_security_group.web.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = local.web_port
  to_port                      = local.web_port
  description                  = "From ALB"
}

resource "aws_vpc_security_group_egress_rule" "web_to_quiz_service" {
  security_group_id            = aws_security_group.web.id
  referenced_security_group_id = aws_security_group.quiz_service.id
  ip_protocol                  = "tcp"
  from_port                    = local.quiz_service_port
  to_port                      = local.quiz_service_port
  description                  = "Proxy /api to quiz-service"
}

resource "aws_vpc_security_group_egress_rule" "web_https" {
  security_group_id = aws_security_group.web.id
  cidr_ipv4         = local.anywhere
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs (ECR, CloudWatch Logs, Secrets Manager)"
}

# ---- quiz-service ----
# マイグレーションの単発タスク（同じイメージを migrate プロファイルで起動する）も、この SG で動かす

resource "aws_security_group" "quiz_service" {
  name        = "${var.name}-quiz-service"
  description = "quiz-service tasks and the migration task"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-quiz-service"
  }
}

resource "aws_vpc_security_group_ingress_rule" "quiz_service_from_web" {
  security_group_id            = aws_security_group.quiz_service.id
  referenced_security_group_id = aws_security_group.web.id
  ip_protocol                  = "tcp"
  from_port                    = local.quiz_service_port
  to_port                      = local.quiz_service_port
  description                  = "From web only"
}

resource "aws_vpc_security_group_egress_rule" "quiz_service_to_db" {
  security_group_id            = aws_security_group.quiz_service.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = local.db_port
  to_port                      = local.db_port
  description                  = "To Aurora"
}

resource "aws_vpc_security_group_egress_rule" "quiz_service_https" {
  security_group_id = aws_security_group.quiz_service.id
  cidr_ipv4         = local.anywhere
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs (ECR, CloudWatch Logs, Secrets Manager)"
}

# ---- Aurora ----
# 外向きのルールは持たない。受けた接続への応答は、SG がステートフルなので通る

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "Aurora PostgreSQL"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-db"
  }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_quiz_service" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.quiz_service.id
  ip_protocol                  = "tcp"
  from_port                    = local.db_port
  to_port                      = local.db_port
  description                  = "From quiz-service only"
}
