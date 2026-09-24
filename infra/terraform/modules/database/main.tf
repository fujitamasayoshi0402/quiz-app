# Aurora PostgreSQL Serverless v2。接続がなければ一時停止し、ストレージの料金だけになる（min 0 ACU）。
#
# 接続するロールは 3 つ。アプリとマイグレーションは、パスワードではなく IAM 認証で接続する（ADR-0014）。
#
# | ロール     | 使う者                         | 認証                                           |
# | ---------- | ------------------------------ | ---------------------------------------------- |
# | quiz_admin | ロールの作成（下の Data API）  | パスワード。RDS が Secrets Manager で管理する  |
# | quiz       | マイグレーションの単発タスク   | IAM（rds-db:connect）                          |
# | quiz_app   | quiz-service                   | IAM（rds-db:connect）                          |
#
# quiz / quiz_app は sql/bootstrap_roles.sql で作る。

locals {
  # ローカル（docker-compose）と揃える
  database_name = "quiz"
  port          = 5432
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_rds_cluster_parameter_group" "this" {
  name   = var.name
  family = "aurora-postgresql${split(".", var.engine_version)[0]}"

  # 暗号化されていない接続を拒否する。IAM 認証も TLS を前提にする
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_rds_cluster" "this" {
  cluster_identifier = var.name
  engine             = "aurora-postgresql"
  engine_version     = var.engine_version
  database_name      = local.database_name
  port               = local.port

  # マスターユーザーのパスワードは RDS が作り、Secrets Manager で管理する。Terraform の state には入らない。
  # 使うのはロールの作成だけ。アプリもマイグレーションもこのユーザーでは接続しない（RLS を素通りするため）
  master_username             = "quiz_admin"
  manage_master_user_password = true

  iam_database_authentication_enabled = true

  # Data API。踏み台なしで SQL を流せる。IAM で認可され、SecurityGroup は通らない
  enable_http_endpoint = true

  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [var.security_group_id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.this.name

  # AWS 管理のキー（aws/rds）。カスタマー管理キーは月額がかかる
  storage_encrypted = true

  serverlessv2_scaling_configuration {
    min_capacity             = 0
    max_capacity             = var.max_capacity
    seconds_until_auto_pause = var.seconds_until_auto_pause
  }

  backup_retention_period = var.backup_retention_days
  copy_tags_to_snapshot   = true

  # 時刻は UTC。日本時間の深夜（3〜4 時にバックアップ、日曜 4〜5 時にメンテナンス）
  preferred_backup_window      = "18:00-19:00"
  preferred_maintenance_window = "sat:19:00-sat:20:00"

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = var.deletion_protection ? "${var.name}-final" : null

  apply_immediately = var.apply_immediately
}

resource "aws_rds_cluster_instance" "writer" {
  identifier         = "${var.name}-writer"
  cluster_identifier = aws_rds_cluster.this.id
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version
  instance_class     = "db.serverless"

  # マイナーバージョンも engine_version で上げる。自動で上がると、コードと実物がずれる
  auto_minor_version_upgrade = false

  apply_immediately = var.apply_immediately
}

# ---- ロールの作成 ----
# Data API で、マスターユーザーとして SQL を流す。SQL は何度流しても同じ結果になるように書いてあり、
# 中身を変えると次の apply でもう一度流れる。
#
# apply する環境に AWS CLI が要る。
# 一時停止から復帰している間は DatabaseResumingException が返るため、数回やり直す。

resource "terraform_data" "bootstrap_roles" {
  triggers_replace = {
    cluster = aws_rds_cluster.this.cluster_resource_id
    sql     = filesha256("${path.module}/sql/bootstrap_roles.sql")
  }

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    command     = <<-EOT
      set -u
      for attempt in 1 2 3 4 5 6; do
        if aws rds-data execute-statement \
            --region "$REGION" \
            --resource-arn "$CLUSTER_ARN" \
            --secret-arn "$SECRET_ARN" \
            --database "$DATABASE" \
            --sql "file://$SQL_FILE" > /dev/null; then
          exit 0
        fi
        echo "ロールの作成に失敗した（$attempt 回目）。20 秒後にやり直す" >&2
        sleep 20
      done
      exit 1
    EOT

    environment = {
      REGION      = data.aws_region.current.region
      CLUSTER_ARN = aws_rds_cluster.this.arn
      SECRET_ARN  = aws_rds_cluster.this.master_user_secret[0].secret_arn
      DATABASE    = local.database_name
      SQL_FILE    = abspath("${path.module}/sql/bootstrap_roles.sql")
    }
  }

  # 書き込み先のインスタンスができるまで、SQL は受け付けられない
  depends_on = [aws_rds_cluster_instance.writer]
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}
