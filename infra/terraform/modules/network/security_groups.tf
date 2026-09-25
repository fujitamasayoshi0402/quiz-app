# 通信の許可。外から届く入口は ALB だけにし、あとは 1 段ずつ隣へ渡す。
#
#   インターネット → ALB → quiz-service → Aurora
#
# ECS のタスクはパブリック IP を持つ（ADR-0013）。**タスクへの受信は SG の参照だけで許し、CIDR では開けない。**
# CIDR で開けると、ALB を通らずタスクへ直接届く。
#
# web は Amplify Hosting で配り、VPC の外から ALB を呼ぶ（ADR-0012）。
# スタブ認証の間、ALB は web の proxy だけが知る秘密のヘッダを確かめる（modules/quiz-service）。

locals {
  alb_port          = 443
  quiz_service_port = 8080
  db_port           = 5432

  anywhere = "0.0.0.0/0"
}

# SG の description は英数字しか使えず、変えると作り直しになる

# ---- ALB ----

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "ALB in front of quiz-service"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-alb"
  }
}

# HTTPS だけを受け、誰からでも受ける。web（Amplify）の実行環境は送信元の IP が決まらないため、IP では絞れない。
# アプリに届くかどうかは、ALB のリスナーが秘密のヘッダで決める（modules/quiz-service）。
# HTTP は受けない。HTTP で届いた時点で、ヘッダが平文で流れてしまう
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = local.anywhere
  ip_protocol       = "tcp"
  from_port         = local.alb_port
  to_port           = local.alb_port
  description       = "HTTPS from anywhere"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_quiz_service" {
  security_group_id            = aws_security_group.alb.id
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

resource "aws_vpc_security_group_ingress_rule" "quiz_service_from_alb" {
  security_group_id            = aws_security_group.quiz_service.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = local.quiz_service_port
  to_port                      = local.quiz_service_port
  description                  = "From ALB only"
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
