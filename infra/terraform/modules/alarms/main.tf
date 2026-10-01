# アラームの送り先（DEV-108）。環境に 1 つ置き、各モジュールのアラームがここへ送る。
# どのアラームを置いているか、鳴ったら何を見るかは、開発ガイドラインの「アラーム」にある。
#
# 送るのは CloudWatch のアラームと、EventBridge のルール（ECS のタスクが止まったなど）。
# 既定のトピックのポリシーは、EventBridge からの送信を許さない。両方を許すポリシーを置く

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

resource "aws_sns_topic" "this" {
  name = "${var.name}-alarms"
}

# メールの購読は、届いた確認のメールのリンクを開くまで有効にならない
resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.this.arn
  protocol  = "email"
  endpoint  = var.email
}

resource "aws_sns_topic_policy" "this" {
  arn = aws_sns_topic.this.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "CloudWatchAlarms"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.this.arn
        Condition = {
          ArnLike = { "aws:SourceArn" = "arn:aws:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:${var.name}-*" }
        }
      },
      {
        # ルールは各モジュールが作る。名前の頭で絞る
        Sid       = "EventBridgeRules"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.this.arn
        Condition = {
          ArnLike = { "aws:SourceArn" = "arn:aws:events:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:rule/${var.name}-*" }
        }
      },
    ]
  })
}
