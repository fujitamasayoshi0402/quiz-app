# quiz-app

技術知識を題材としたクイズアプリです。AWS / インフラ、イベント駆動・マイクロサービス設計、
認証認可、バックエンド設計といった領域を、4 択問題と図解つき解説で確認できます。

一般ユーザーはカテゴリ / 難易度を選んでクイズに回答でき、管理者はクイズ・カテゴリ・難易度を
CRUD し、文章と draw.io による図解で解説を登録できます。認証にはパスキー（WebAuthn）を採用しています。

実装だけでなく、設計判断の記録（ADR）、Terraform による IaC、GitHub Actions での CI/CD、
監視・運用設計までを一貫して扱うことを方針としています。

## 技術スタック

| 領域 | 技術 |
| --- | --- |
| バックエンド | Kotlin + Spring Boot 3 (Java 21) |
| フロントエンド | Next.js (App Router) + TypeScript + Tailwind CSS |
| DB | Aurora PostgreSQL Serverless v2 (min 0 ACU) |
| 認証 | Amazon Cognito（パスキー / WebAuthn） |
| 実行基盤 | ECS Fargate + ALB |
| 非同期 / 通知 | EventBridge + Lambda + Slack Webhook |
| IaC | Terraform |
| CI/CD | GitHub Actions（OIDC） |

## ドキュメント

- [ロードマップ](docs/ROADMAP.md)
- [ADR](docs/adr/)
- [開発ガイドライン](docs/development-guidelines.md)

## 設計方針

- **常にデプロイ可能な状態を保つ** — 機能を増やす前に CI/CD を通し、以降は「マージすれば動く」状態を維持する
- **判断の根拠を残す** — 技術選定とトレードオフは [ADR](docs/adr/) に記録する
- **段階的にサービスを分割する** — ドメイン境界が固まるまでは単一サービスで実装し、DB スキーマとドメインイベントで境界のみ先に引く
- **コストを設計に織り込む** — Aurora Serverless v2 の min 0 ACU、NAT Gateway 不使用、イベント駆動による常駐リソースの削減

詳細は [ロードマップ](docs/ROADMAP.md) を参照してください。

## ステータス

基盤構築中（Phase 0）
