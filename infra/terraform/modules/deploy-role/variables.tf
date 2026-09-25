variable "name" {
  description = "リソース名の接頭辞（例: quiz-app-dev）"
  type        = string
}

# ---- 信頼の条件 ----

variable "github_subject_prefix" {
  description = "ロールを引き受けられるリポジトリ。OIDC のトークンの sub に入る表記（gh api repos/<owner>/<name>/actions/oidc/customization/sub の sub_claim_prefix）"
  type        = string
}

variable "github_environment" {
  description = "ロールを引き受けられる GitHub の Environment。どのブランチから使えるかは Environment 側で絞る"
  type        = string
}

# ---- デプロイする対象（modules/quiz-service） ----

variable "ecr_repository_arn" {
  type = string
}

variable "ecs_cluster_arn" {
  type = string
}

variable "ecs_service_arn" {
  type = string
}

variable "task_definition_families" {
  description = "新しいリビジョンを登録するタスク定義。migrate は単発タスクとして流す"
  type = object({
    app     = string
    migrate = string
  })
}

variable "task_role_arns" {
  description = "タスク定義に書かれているロール（実行ロールとタスクロール）。登録と起動のときに渡す（iam:PassRole）"
  type        = list(string)
}

# ---- web（modules/web） ----

variable "amplify_branch_arn" {
  description = "ビルドを起動する Amplify のブランチ。quiz-service のデプロイの後に起動する"
  type        = string
}
