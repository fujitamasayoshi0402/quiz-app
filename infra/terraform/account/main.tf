# アカウント全体に 1 つだけ置くもの（予算、コスト配分タグ）。環境（envs/*）に属さないため、ここで持つ。
#
# 予算は Phase 0 にコンソールで作っていたものを、import で Terraform の管理に移した（DEV-51）。

data "aws_caller_identity" "current" {}

# ---- 予算 ----

# 月の費用の上限の目安。実績が 85% と 100% を超えたとき、月末の予測が 100% を超えたときにメールで知らせる。
# 予測で知らせるのは、月の途中で止める判断をするため。実績だけだと、気づいたときには超えている
resource "aws_budgets_budget" "monthly" {
  name         = "月次コスト予算アラート"
  budget_type  = "COST"
  limit_amount = "30.0"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # ここから 3 つは、コンソールで作ったときの既定値。書かないと取り込んだあとに消されてしまう
  billing_view_arn = "arn:aws:billing::${data.aws_caller_identity.current.account_id}:billingview/primary"
  metrics          = ["UnblendedCost"]

  # クレジットと返金は差し引かない。クレジットで相殺されて、使った量が見えなくなるのを避ける
  filter_expression {
    not {
      dimensions {
        key    = "RECORD_TYPE"
        values = ["Credit", "Refund"]
      }
    }
  }

  notification {
    notification_type          = "ACTUAL"
    comparison_operator        = "GREATER_THAN"
    threshold                  = 85
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = [var.notification_email]
  }

  notification {
    notification_type          = "ACTUAL"
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = [var.notification_email]
  }

  notification {
    notification_type          = "FORECASTED"
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = [var.notification_email]
  }
}

# ---- コスト配分タグ ----

# default_tags で全リソースに付けているタグを、Cost Explorer と予算で使えるようにする。
# Env で環境ごと、Project でこのアプリの分だけの費用を見る
resource "aws_ce_cost_allocation_tag" "this" {
  for_each = toset(["Project", "Env"])

  tag_key = each.key
  status  = "Active"
}
