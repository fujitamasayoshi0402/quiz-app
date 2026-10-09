# Architecture Decision Records

アーキテクチャ上の意思決定とその理由を記録します。形式は [MADR](https://adr.github.io/madr/) に準拠します。

## テーマ別

領域ごとに、読む順に並べています。1 つの ADR が複数の領域にまたがるときは、主な領域にだけ載せています。全体を置き換えられた ADR は載せず、[置き換えと見直し](#置き換えと見直し)から引きます。

| 領域 | ADR |
| --- | --- |
| 進め方 | [0001](0001-record-architecture-decisions.md) ADR を残す / [0004](0004-split-services-incrementally.md) サービスは段階的に分ける / [0023](0023-keep-answer-as-module-in-quiz-service.md) 回答・採点はモジュールのまま分けない |
| 技術スタック | [0002](0002-use-kotlin-and-spring-boot.md) Kotlin + Spring Boot / [0008](0008-use-spring-boot-4.md) Spring Boot 4 / [0009](0009-use-spring-data-jdbc.md) Spring Data JDBC / [0003](0003-use-nextjs-for-frontend.md) Next.js |
| テナントとデータ | [0006](0006-row-level-multi-tenancy.md) 行単位のマルチテナント（RLS） / [0007](0007-soft-delete-master-data.md) マスタデータの論理削除 / [0025](0025-let-signed-in-users-join-public-tenants.md) 公開テナントへの参加 |
| 認証 | [0016](0016-authenticate-with-cognito-managed-login.md) Cognito の Managed Login、利用者の ID を切り離す / [0027](0027-design-self-hosted-passkeys-and-keep-cognito.md) パスキーの自前実装の設計。いまは Cognito を使い続ける |
| API | [0010](0010-generate-openapi-from-code.md) OpenAPI をコードから生成する / [0019](0019-expose-api-through-api-gateway-http-api.md) 入口は API Gateway（HTTP API） |
| 解説と解説図 | [0018](0018-write-explanations-in-markdown-and-render-on-screen.md) 解説は Markdown / [0017](0017-deliver-figures-with-cloudfront-signed-urls.md) 図は CloudFront の署名付き URL で配る / [0020](0020-reference-figures-from-explanations-and-add-images-and-pdfs.md) 画像と PDF、`figure:` で指す / [0021](0021-render-first-page-of-pdfs-as-images.md) PDF の 1 ページ目を画像にする |
| イベントと通知 | [0022](0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md) Outbox から EventBridge、テナントごとの Slack へ |
| インフラ | [0011](0011-terraform-state-and-environments.md) tfstate と環境の分け方 / [0012](0012-serve-frontend-on-amplify-hosting.md) フロントは Amplify Hosting / [0013](0013-run-ecs-tasks-in-public-subnets.md) タスクはパブリックサブネット（NAT なし） / [0014](0014-connect-to-aurora-with-iam-auth.md) Aurora へ IAM 認証 |
| デプロイとリリース | [0015](0015-deploy-by-registering-task-definitions-from-ci.md) CI がタスク定義を登録する / [0024](0024-run-prod-in-same-account-and-launch-at-release.md) prod は同じアカウント、公開のときから動かす |
| 運用 | [0026](0026-trace-requests-with-micrometer-and-x-ray.md) 分散トレース（Micrometer、X-Ray） |

## 置き換えと見直し

**Accepted にした ADR は書き換えない**ため、決定を変えたときは新しい ADR が前のものを指す。古いほうを読むときは、ここで後の決定を確かめる。

| 前の ADR | 後の ADR | 変わったこと |
| --- | --- | --- |
| [0005](0005-use-cognito-passkeys.md) | [0016](0016-authenticate-with-cognito-managed-login.md) | Cognito のパスキーと Group のロールから、Managed Login（パスワードとパスキー）に。ロールと利用者の ID はアプリのデータで持つ（全体を置き換え） |
| [0012](0012-serve-frontend-on-amplify-hosting.md) | [0019](0019-expose-api-through-api-gateway-http-api.md) | quiz-service の入口を ALB から API Gateway に替え、秘密のヘッダをやめた（一部を置き換え） |
| [0020](0020-reference-figures-from-explanations-and-add-images-and-pdfs.md) | [0021](0021-render-first-page-of-pdfs-as-images.md) | 0020 で後に回した PDF の見せ方を決めた。リンクで開くのに加え、1 ページ目を画像にして解説の中に出す（一部を置き換え） |
| [0016](0016-authenticate-with-cognito-managed-login.md) | [0027](0027-design-self-hosted-passkeys-and-keep-cognito.md) | 自前の実装への差し替えを設計したうえで、見送った。0016 は有効のまま（見直して変えなかった） |
| [0004](0004-split-services-incrementally.md) | [0022](0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)、[0023](0023-keep-answer-as-module-in-quiz-service.md) | 未決にしていたイベントの送り方と、回答・採点を分けるかを決めた（未決の点を決めた） |

## 一覧

| # | タイトル | ステータス |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | アーキテクチャ決定記録を採用する | Accepted |
| [0002](0002-use-kotlin-and-spring-boot.md) | バックエンドに Kotlin + Spring Boot を採用する | Accepted |
| [0003](0003-use-nextjs-for-frontend.md) | フロントエンドに Next.js を採用する | Accepted |
| [0004](0004-split-services-incrementally.md) | マイクロサービスへの分割は段階的に行う | Accepted |
| [0005](0005-use-cognito-passkeys.md) | 認証に Amazon Cognito のパスキーを採用する | Superseded by [0016](0016-authenticate-with-cognito-managed-login.md) |
| [0006](0006-row-level-multi-tenancy.md) | マルチテナントをロウ単位の分離で実現する | Accepted |
| [0007](0007-soft-delete-master-data.md) | マスタデータを論理削除する | Accepted |
| [0008](0008-use-spring-boot-4.md) | Spring Boot 4 を採用する | Accepted |
| [0009](0009-use-spring-data-jdbc.md) | データアクセスに Spring Data JDBC を採用する | Accepted |
| [0010](0010-generate-openapi-from-code.md) | OpenAPI 定義はコードから生成し、スナップショットを固定する | Accepted |
| [0011](0011-terraform-state-and-environments.md) | tfstate は 1 つの S3 バケットにキーで分けて置き、環境はルートモジュールで分ける | Accepted |
| [0012](0012-serve-frontend-on-amplify-hosting.md) | フロントは Amplify Hosting で配る | Accepted（quiz-service の公開の仕方は [0019](0019-expose-api-through-api-gateway-http-api.md) で置き換え） |
| [0013](0013-run-ecs-tasks-in-public-subnets.md) | ECS のタスクはパブリックサブネットに置き、NAT Gateway も VPC Endpoint も使わない | Accepted |
| [0014](0014-connect-to-aurora-with-iam-auth.md) | Aurora へは IAM 認証で接続し、ロールは Data API で作る | Accepted |
| [0015](0015-deploy-by-registering-task-definitions-from-ci.md) | dev へのデプロイは GitHub Actions がタスク定義のリビジョンを登録して行い、Terraform はタスク定義の形だけを持つ | Accepted |
| [0016](0016-authenticate-with-cognito-managed-login.md) | 認証は Cognito の Managed Login とパスキーで作り、利用者の ID を Cognito から切り離す | Accepted（自前の実装への差し替えは [0027](0027-design-self-hosted-passkeys-and-keep-cognito.md) で見送り） |
| [0017](0017-deliver-figures-with-cloudfront-signed-urls.md) | 解説図は非公開の S3 に置き、API が出す CloudFront の署名付き URL で配る | Accepted |
| [0018](0018-write-explanations-in-markdown-and-render-on-screen.md) | 解説は Markdown の原文で持ち、画面で変換する。生の HTML は通さない | Accepted |
| [0019](0019-expose-api-through-api-gateway-http-api.md) | API の入口を ALB から API Gateway（HTTP API）に替え、秘密のヘッダをやめる | Accepted |
| [0020](0020-reference-figures-from-explanations-and-add-images-and-pdfs.md) | 解説図に画像と PDF を加え、解説の本文から `figure:` の ID で指す | Accepted（PDF の見せ方は [0021](0021-render-first-page-of-pdfs-as-images.md) で置き換え） |
| [0021](0021-render-first-page-of-pdfs-as-images.md) | PDF の 1 ページ目を置くときに画像にし、解説の中に出す | Accepted |
| [0022](0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md) | クイズの変更を Outbox から EventBridge に送り、テナントごとの Slack へ Lambda が知らせる | Accepted |
| [0023](0023-keep-answer-as-module-in-quiz-service.md) | 回答・採点は quiz-service の中のモジュールのまま分けない。分けるときの通信の使い分けを先に決めておく | Accepted |
| [0024](0024-run-prod-in-same-account-and-launch-at-release.md) | prod は同じアカウントに環境として置き、常時動かすのは公開のときから。main へのマージと承認で載せる | Accepted |
| [0025](0025-let-signed-in-users-join-public-tenants.md) | 公開テナントには、ログインした人が承認なしで自分で参加する。一覧と参加の API はテナントの外の `/api/me` に置く | Accepted |
| [0026](0026-trace-requests-with-micrometer-and-x-ray.md) | 分散トレースは Micrometer Tracing（OpenTelemetry）で取り、同じタスクのコレクタから X-Ray へ送る。イベントは Outbox に文脈を残してつなぐ | Accepted |
| [0027](0027-design-self-hosted-passkeys-and-keep-cognito.md) | パスキーを自前で実装するときの設計を決め、いまは Cognito を使い続ける | Accepted |

## 運用ルール

- ファイル名は `NNNN-kebab-case-title.md`。番号は連番で、欠番を作らない
- ステータスは `Proposed` / `Accepted` / `Deprecated` / `Superseded by ADR-NNNN`
- **一度 Accepted にした ADR は書き換えない**。決定を変える場合は新しい ADR を起こし、旧 ADR を `Superseded` にする
- 新規作成時は [0000-template.md](0000-template.md) をコピーする
- 足したら、[一覧](#一覧)と[テーマ別](#テーマ別)の両方に載せる。前の決定を変えるときは、[置き換えと見直し](#置き換えと見直し)にも足し、前の ADR のステータスに後の ADR を書き添える
