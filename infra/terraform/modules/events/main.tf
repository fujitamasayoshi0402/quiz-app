# クイズのイベントを流すカスタムバス（ADR-0022）。
#
#   quiz-service（Outbox からコミットの直後に送る）→ このバス → ルール → notification-service（DEV-98）
#
# 既定のバスは使わない。AWS のサービスのイベントと混ざらず、送れる者をバスの ARN で絞れる。
# 届いたかどうかは、アーカイブのイベント数で確かめる（開発ガイドライン「イベント（EventBridge）」）。
# **全イベントをログに流すルールは置かない。** イベントには問題文の冒頭とテナントの名前が入り、運用者のログに残ることになる

resource "aws_cloudwatch_event_bus" "this" {
  name = var.name
}

# 7 日分を残し、受け手を直したあとに流し直せるようにする。受け手はイベントの ID で重複を捨てるため、流し直しても二重には届かない。
# Outbox の送れた行を消すまでの時間（7 日）と揃える
resource "aws_cloudwatch_event_archive" "this" {
  name             = var.name
  event_source_arn = aws_cloudwatch_event_bus.this.arn
  retention_days   = var.archive_retention_days
}
