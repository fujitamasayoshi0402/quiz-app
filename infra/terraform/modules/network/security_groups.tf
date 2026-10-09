# 通信の許可。外から届く入口は API Gateway だけにし、あとは 1 段ずつ隣へ渡す。
#
#   インターネット → API Gateway → VPC リンク → quiz-service → Aurora
#
# API Gateway は VPC の外にあり、VPC リンクの ENI を通って VPC に入る（ADR-0019）。
# web は Amplify Hosting で配り、VPC の外から API Gateway を呼ぶ（ADR-0012）。
#
# ECS のタスクはパブリック IP を持つ（ADR-0013）。**タスクへの受信は SG の参照だけで許し、CIDR では開けない。**
# CIDR で開けると、入口を通らずタスクへ直接届く。

locals {
  quiz_service_port = 8080
  db_port           = 5432

  anywhere = "0.0.0.0/0"
}

# SG の description は英数字しか使えず、変えると作り直しになる

# ---- API Gateway の VPC リンク ----
# API Gateway（HTTP API）が VPC の中に置く ENI に付ける（modules/quiz-service の api.tf）。
# 受信のルールは持たない。接続はすべて VPC リンクの側から始まり、応答は SG がステートフルなので通る

resource "aws_security_group" "vpc_link" {
  name        = "${var.name}-vpc-link"
  description = "API Gateway VPC link to quiz-service"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-vpc-link"
  }
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_quiz_service" {
  security_group_id            = aws_security_group.vpc_link.id
  referenced_security_group_id = aws_security_group.quiz_service.id
  ip_protocol                  = "tcp"
  from_port                    = local.quiz_service_port
  to_port                      = local.quiz_service_port
  description                  = "Forward to quiz-service"
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

resource "aws_vpc_security_group_ingress_rule" "quiz_service_from_vpc_link" {
  security_group_id            = aws_security_group.quiz_service.id
  referenced_security_group_id = aws_security_group.vpc_link.id
  ip_protocol                  = "tcp"
  from_port                    = local.quiz_service_port
  to_port                      = local.quiz_service_port
  description                  = "From API Gateway VPC link"
}

resource "aws_vpc_security_group_egress_rule" "quiz_service_to_db" {
  security_group_id            = aws_security_group.quiz_service.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = local.db_port
  to_port                      = local.db_port
  description                  = "To Aurora"
}

# ECR からのイメージの取得、CloudWatch Logs。いずれも公開エンドポイントへ HTTPS で出る
resource "aws_vpc_security_group_egress_rule" "quiz_service_https" {
  security_group_id = aws_security_group.quiz_service.id
  cidr_ipv4         = local.anywhere
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs (ECR, CloudWatch Logs)"
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
