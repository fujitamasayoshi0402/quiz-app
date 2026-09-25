# quiz-service のイメージ。タグには git のコミットを使う
resource "aws_ecr_repository" "quiz_service" {
  name = "${var.name}/quiz-service"

  # 同じタグを別の中身で上書きさせない。動いているタスクがどのコミットかを、タグで言い切れるようにする
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.force_delete_images

  # 基本スキャン（無料）。既知の脆弱性を push のたびに調べる
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "quiz_service" {
  repository = aws_ecr_repository.quiz_service.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        # 戻せる範囲。ストレージの料金はほぼかからないが、際限なく積まない
        rulePriority = 2
        description  = "Keep the latest 10 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = { type = "expire" }
      },
    ]
  })
}
