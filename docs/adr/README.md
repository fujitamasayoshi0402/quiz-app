# Architecture Decision Records

アーキテクチャ上の意思決定とその理由を記録します。形式は [MADR](https://adr.github.io/madr/) に準拠します。

## 一覧

| # | タイトル | ステータス |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | アーキテクチャ決定記録を採用する | Accepted |
| [0002](0002-use-kotlin-and-spring-boot.md) | バックエンドに Kotlin + Spring Boot を採用する | Accepted |
| [0003](0003-use-nextjs-for-frontend.md) | フロントエンドに Next.js を採用する | Accepted |
| [0004](0004-split-services-incrementally.md) | マイクロサービスへの分割は段階的に行う | Accepted |
| [0005](0005-use-cognito-passkeys.md) | 認証に Amazon Cognito のパスキーを採用する | Accepted |
| [0006](0006-row-level-multi-tenancy.md) | マルチテナントをロウ単位の分離で実現する | Accepted |
| [0007](0007-soft-delete-master-data.md) | マスタデータを論理削除する | Accepted |
| [0008](0008-use-spring-boot-4.md) | Spring Boot 4 を採用する | Accepted |
| [0009](0009-use-spring-data-jdbc.md) | データアクセスに Spring Data JDBC を採用する | Accepted |
| [0010](0010-generate-openapi-from-code.md) | OpenAPI 定義はコードから生成し、スナップショットを固定する | Accepted |
| [0011](0011-terraform-state-and-environments.md) | tfstate は 1 つの S3 バケットにキーで分けて置き、環境はルートモジュールで分ける | Accepted |

## 運用ルール

- ファイル名は `NNNN-kebab-case-title.md`。番号は連番で、欠番を作らない
- ステータスは `Proposed` / `Accepted` / `Deprecated` / `Superseded by ADR-NNNN`
- **一度 Accepted にした ADR は書き換えない**。決定を変える場合は新しい ADR を起こし、旧 ADR を `Superseded` にする
- 新規作成時は [0000-template.md](0000-template.md) をコピーする
