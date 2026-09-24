# quiz-app

カテゴリと難易度を自由に定義できるクイズアプリです。特定の分野に依存せず、扱う内容はすべてデータとして登録します。

管理者はそれぞれ独立したクイズ空間（テナント）を持ちます。管理者が登録したカテゴリ・クイズは
そのテナントの中だけに存在し、他のテナントからは見えません。
一般ユーザーは所属するテナントのクイズに回答し、正誤判定と解説を確認できます。
認証にはパスキー（WebAuthn）を採用しています。

デモ環境には技術系のクイズ（AWS / インフラ、イベント駆動・マイクロサービス設計、認証認可、
バックエンド設計など）を収録しています。

実装だけでなく、設計判断の記録（ADR）、Terraform による IaC、GitHub Actions での CI/CD、
監視・運用設計までを一貫して扱うことを方針としています。

## 技術スタック

| 領域 | 技術 |
| --- | --- |
| バックエンド | Kotlin 2.3 + Spring Boot 4.1 (Java 21) |
| フロントエンド | Next.js (App Router) + TypeScript + Tailwind CSS |
| DB | Aurora PostgreSQL Serverless v2 (min 0 ACU) |
| 認証 | Amazon Cognito（パスキー / WebAuthn） |
| 実行基盤 | ECS Fargate + ALB |
| 非同期 / 通知 | EventBridge + Lambda + Slack Webhook |
| IaC | Terraform |
| CI/CD | GitHub Actions（OIDC） |
| API 定義 | OpenAPI（コードから生成し、フロントの型を自動生成） |

## ディレクトリ構成

```
.
├── apps/
│   └── web/                     # Next.js フロントエンド
├── services/
│   ├── quiz-service/            # クイズ / カテゴリ / 難易度の CRUD、出題と採点
│   └── notification-service/    # EventBridge から起動し Slack へ通知する Lambda
├── infra/
│   └── terraform/
│       ├── modules/             # 再利用するモジュール
│       └── envs/                # dev / prod の環境定義
├── docs/
│   ├── adr/                     # アーキテクチャ決定記録（MADR 形式）
│   ├── architecture/            # C4 図・draw.io 原本
│   └── api/                     # OpenAPI 定義
└── .github/workflows/           # GitHub Actions のワークフロー
```

サービスの境界は DB スキーマ単位で分離します。回答・採点・履歴を担う `answer-service` は、
当面 `quiz-service` 内のモジュールとして実装し、ドメイン境界が安定してから物理的に分割します。
理由は [ADR-0004](docs/adr/0004-split-services-incrementally.md) を参照してください。

## 動かしてみる

Docker だけで起動できます。

```bash
docker compose up
```

`http://localhost:3000` を開き、利用者を選ぶと、デモ用のクイズに回答できます。
「デモ管理者」を選ぶと、所属する 2 つのテナントから入る先を選べます。
管理者として所属するテナントでは、ヘッダの「管理」からクイズやカテゴリを編集できます。

| | 場所 |
| --- | --- |
| アプリ | `localhost:3000` |
| API 定義（Swagger UI） | `localhost:8080/swagger-ui.html` |
| PostgreSQL | `localhost:5432` |
| LocalStack | `localhost:4566` |

ポートがほかのアプリと重なるときは、`.env.example` を `.env` にコピーして変えてください。

## ローカル開発

開発ツールのバージョンは [mise](https://mise.jdx.dev/) で固定しています（Java 21 / Node.js 22 / pnpm）。
アプリをホストで動かすときは、依存サービスだけをコンテナで起動します。

```bash
brew install mise
echo 'eval "$(mise activate zsh)"' >> ~/.zshrc   # 初回のみ
mise install                                     # .mise.toml のバージョンを導入

docker compose up -d postgres localstack
SPRING_PROFILES_ACTIVE=dev ./gradlew :services:quiz-service:bootRun
pnpm install && pnpm --filter web dev
```

`dev` プロファイルで起動すると、デモ用のカテゴリとクイズが投入されます。

詳細は [開発ガイドライン](docs/development-guidelines.md#7-ローカル開発) を参照してください。

## ドキュメント

- [ロードマップ](docs/ROADMAP.md)
- [ADR](docs/adr/)
- [開発ガイドライン](docs/development-guidelines.md)
- [要件定義](docs/requirements.md)
- [ドメインモデル](docs/domain-model.md)
- [DB スキーマ設計](docs/db-schema.md)
- [OpenAPI 定義](docs/api/openapi.yaml)

## 設計方針

- **常にデプロイ可能な状態を保つ** — 機能を増やす前に CI/CD を通し、以降は「マージすれば動く」状態を維持する
- **判断の根拠を残す** — 技術選定とトレードオフは [ADR](docs/adr/) に記録する
- **段階的にサービスを分割する** — ドメイン境界が固まるまでは単一サービスで実装し、DB スキーマとドメインイベントで境界のみ先に引く
- **コストを設計に織り込む** — Aurora Serverless v2 の min 0 ACU、NAT Gateway 不使用、イベント駆動による常駐リソースの削減

詳細は [ロードマップ](docs/ROADMAP.md) を参照してください。

## ステータス

Phase 1（ローカルで動く MVP）を実装中です。

バックエンドは、カテゴリ・難易度・クイズの CRUD、出題、挑戦と回答、
テナント単位の認可までが動作します。行レベルセキュリティによるテナント分離、
OpenAPI 定義の自動生成、PR ごとの lint / test / build も入っています。
フロントエンドはこれから実装します。

進捗は [ロードマップ](docs/ROADMAP.md) を参照してください。
