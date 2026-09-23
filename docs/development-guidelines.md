# 開発ガイドライン

## 1. プロジェクト概要

カテゴリと難易度を自由に定義できる **クイズアプリ**。
管理者が 4 択問題と図解つきの解説を登録し、一般ユーザーがカテゴリ / 難易度を選んで回答する。

**特定の分野に依存しない汎用的なアプリとして設計する。** 扱う分野はアプリの仕様ではなくデータであり、
カテゴリ・難易度はすべて管理画面から CRUD する。分野を前提にしたロジックやスキーマは持たない。

- 一般ユーザー: カテゴリ / 難易度を選んでクイズに回答する
- 管理者: クイズ・カテゴリ・難易度を CRUD する
- リポジトリ: https://github.com/fujitamasayoshi0402/quiz-app （Public / monorepo）
- タスク管理: Jira（課題キー `DEV-*`）
- インフラ: AWS（Terraform で IaC 管理）
- 重視する観点: 設計判断の記録（ADR）・IaC・CI/CD・認証・運用設計・コスト最適化

## 2. 機能要件

### テナント

管理者ごとに独立したクイズ空間を持つ。これを **テナント** と呼ぶ（[ADR-0006](adr/0006-row-level-multi-tenancy.md)）。

- 管理者は招待制で増える。既存の管理者が招待したメールアドレスのみ管理者になれる
- 一般ユーザーはテナントに所属し、そのテナントのクイズに回答する
- テナントは URL のパスで識別する（`/t/{slug}/...`）
- テナントは `private` / `public` の公開設定を持つ。`public` は将来の機能とし、
  Phase 1〜3 では `private` のみを扱う

ユースケース・画面一覧・URL 構成は [要件定義](requirements.md)、各概念の持ち方は [ドメインモデル](domain-model.md) を参照。

### 一般ユーザー
- パスキー（WebAuthn）でユーザー登録・ログイン
- 所属するテナントのカテゴリ / 難易度を選択してクイズに挑戦
- 4 択問題に回答、正誤判定と解説（文章 + draw.io 図解）の閲覧
- 回答履歴・スコアの確認

### 管理者
- パスキーでの管理者登録・ログイン（一般ユーザーとロール分離）
- 招待による管理者・一般ユーザーの追加
- 自テナントのクイズの CRUD（4 択・正解・解説文・解説図）
- カテゴリ / 難易度の CRUD と、それに紐づくクイズ作成
- 解説図は draw.io（`.drawio` ファイル）で作成し、SVG に書き出して配信

### 共通 / 運用
- クイズの追加・更新時に Slack 通知
- まずは Web アプリ、将来的にスマホアプリ展開

## 3. 技術スタック

| 領域 | 採用技術 | 備考 |
| --- | --- | --- |
| バックエンド | Kotlin 2.3 + Spring Boot 4.1 (Java 21) | Gradle Kotlin DSL。[ADR-0008](adr/0008-use-spring-boot-4.md) |
| DB | Aurora PostgreSQL Serverless v2（min 0 ACU / 自動一時停止） | サービスごとにスキーマ分離。コスト最優先 |
| マイグレーション | Flyway | |
| フロントエンド | Next.js (App Router) + TypeScript + Tailwind CSS + shadcn/ui | TanStack Query / Zod |
| 認証 | Amazon Cognito（パスキー / WebAuthn）+ Group でユーザー・管理者を分離 | |
| コンテナ基盤 | ECS Fargate + ALB | Kubernetes は採用しない |
| 非同期 / 通知 | EventBridge → Lambda → Slack Incoming Webhook（DLQ に SQS） | 常駐リソースを増やさない |
| ファイル | S3 + CloudFront（`.drawio` 原本と SVG） | |
| IaC | Terraform（tfstate は S3 + ロック） | |
| CI/CD | GitHub Actions（AWS 認証は OIDC、アクセスキー禁止） | |
| 監視 | CloudWatch Logs / Metrics、OpenTelemetry | |
| 将来のモバイル | React Native (Expo) | TypeScript 資産を再利用 |

## 4. アーキテクチャ方針

マイクロサービスで構築するが、**最初から細かく割らない**。サービス境界は DB スキーマ単位で分離する。

- `quiz-service` … クイズ / カテゴリ / 難易度の CRUD、出題
- `answer-service` … 回答受付、採点、履歴、スコア（当面は quiz-service 内のモジュールとして実装）
- `notification-service` … EventBridge ルールから起動する Lambda。Slack へ通知
- 認証は Cognito（マネージド）に寄せ、自前の auth-service は作らない
- サービス間は同期 REST を最小限にし、状態変化は EventBridge 経由のイベントで伝搬

### テナントの分離

テナントは単一スキーマ内の `tenant_id` 列で分離する（[ADR-0006](adr/0006-row-level-multi-tenancy.md)）。
**テナント境界を越えた参照は機能不全ではなく情報漏洩である**ため、次の多層で防ぐ。

- テナント配下の全テーブルに PostgreSQL の行レベルセキュリティ（RLS）を設定する。
  アプリケーション側で絞り込みを書き漏らしても DB が行を返さない状態にする
- リポジトリ層の共通機構でテナント条件を強制する。個々の実装者の記憶に依存させない
- 認可は「このリソースにアクセスできるか」という単一の判定に集約する
- 「別テナントの ID を指定したアクセスが結果を返さないこと」を、テナント配下の全エンドポイントで検証する

## 5. ディレクトリ構成（monorepo）

```
.
├── apps/
│   └── web/                 # Next.js フロントエンド
├── services/
│   ├── quiz-service/        # Kotlin + Spring Boot
│   └── notification-service/
├── infra/
│   └── terraform/
│       ├── modules/
│       └── envs/{dev,prod}/
├── docs/
│   ├── ROADMAP.md
│   ├── development-guidelines.md
│   ├── adr/                 # ADR（MADR 形式）
│   ├── architecture/        # C4 図・drawio
│   └── api/                 # OpenAPI
└── .github/workflows/
```

## 6. 開発規約

### Git
- ブランチ: `main`(本番) ← `develop`(統合) ← `feature/*` / `fix/*` / `docs/*` / `chore/*`
- ブランチ名に Jira キーを含める: `feature/DEV-12-add-quiz-crud`
- コミットは Conventional Commits + Jira キー: `feat(quiz): add quiz CRUD API (DEV-12)`
- `main` / `develop` への直 push は Ruleset `protect-main-develop` で禁止。必ず PR 経由でマージする
- force push / ブランチ削除も禁止

### Jira 連携

GitHub for Atlassian により、コミット・ブランチ・PR が Jira 課題の「開発」パネルに自動で紐づく。
紐付けの条件は **ブランチ名・コミットメッセージ・PR タイトルのいずれかに課題キーが含まれていること**であり、
上記のブランチ・コミット規約を守っていれば自動的に満たされる。

Smart Commits（コミットメッセージからの課題操作）は**紐付けのみを使い、ステータス遷移には使わない**。

- **コミットメッセージは後から修正できない。** 誤記でステータスが飛ぶと、履歴に残り続ける
- コミット規約では課題キーが末尾の括弧内にあり（`(DEV-12)`）、`DEV-12 #done` という
  Smart Commit の構文とは並びが異なる。動作が環境に依存する書き方を規約にしない
- ステータスの変更は Jira 上で行う

### API の URL

テナント配下の API は、**触れる人によってパスを分ける。**

| パス | 必要な条件 |
| --- | --- |
| `/api/t/{slug}/admin/...` | 管理者として所属していること |
| `/api/t/{slug}/play/...` | 所属していること |

パスで判定するため、**テナント配下にエンドポイントを足したときに書き忘れても公開されない。**
コントローラごとに注釈を付ける方式だと、付け忘れがそのまま穴になる。

同じクイズでも、管理 API は正解を含み、出題 API は含まない。
1 つの URL に両方の応答を同居させると、権限の掛け違いで正解が漏れる余地が生まれる。

### lint

整形は ktlint、設計の匂いは detekt が見る。役割が違うので両方走らせる。

```bash
./gradlew :services:quiz-service:ktlintFormat   # 自動整形
./gradlew :services:quiz-service:ktlintCheck :services:quiz-service:detekt
```

ktlint の規約は `.editorconfig` が持つ。**`ktlint_code_style` は `intellij_idea` にしている。**
既定の `ktlint_official` は改行の入れ方が強く、IDE の整形結果と食い違うため。
エディタと lint が別々の形を要求する状態にしない。

detekt で既定から変えたルールは `config/detekt.yml` にあり、それぞれ理由を書いてある。
detekt 1.23.8 は Kotlin 2.0 でコンパイルされているため、**detekt のクラスパスだけ 2.0 系に固定**している。

### CI

`.github/workflows/ci.yml` が PR と `develop` / `main` への push で動く。

| ジョブ | 内容 |
| --- | --- |
| `changes` | 変更パスを見て後続を出し分ける |
| `backend` | ktlint / detekt → test（Testcontainers）→ bootJar |
| `frontend` | 型の作り直しに差が出ないか → 型チェック |
| `ci` | 先行ジョブの結果を集約する |

**Ruleset の必須チェックには `ci` だけを指定する。** ジョブを足すたびに設定を触らずに済み、
パスの出し分けでスキップされたジョブが「報告されないまま待ち続ける」状態にもならない。

ツールのバージョンは CI でも `.mise.toml` から取る。CI 側で別に指定すると二重管理になる。

### OpenAPI

定義は `docs/api/openapi.yaml` にある。**手で書かない。** コードから生成して固定している。

```bash
# API を変えたら再生成してコミットする
UPDATE_OPENAPI=true ./gradlew :services:quiz-service:test --tests '*OpenApiSnapshotTest'
pnpm --filter web generate:api
```

再生成を忘れると `OpenApiSnapshotTest` が落ちる。**落ちること自体が仕組み**なので、
テストを直すのではなく定義を再生成する。

フロントの型（`apps/web/src/lib/api/schema.d.ts`）も生成物で、リポジトリに持つ。
生成し直さないと差分が残るため、CI で検出できる。

起動中は Swagger UI から定義を読める。

```
http://localhost:8080/swagger-ui.html
```

**環境で出し分けない。** リポジトリが Public で定義もコミットしてある以上、UI を隠しても何も守れない。
「試す」操作も認証を通るため、公開される範囲は API そのものと変わらない。

### コード
- バックエンド: レイヤード（controller / usecase / domain / infrastructure）、テストは JUnit5 + Testcontainers
- フロント: Server Components 優先、API 呼び出しは TanStack Query、型は Zod でバリデーション
- API 定義はコードから生成し、`docs/api/openapi.yaml` に固定する（[ADR-0010](adr/0010-generate-openapi-from-code.md)）。
  **真実はコードであり、定義はその写像。** フロントの型は固定した定義から生成する

### ドキュメント
- 技術選定・設計判断は必ず [ADR](adr/) に残す。運用ルールは [docs/adr/README.md](adr/README.md) を参照
- 記録対象は「後から変更するのが高くつく決定」に限定する。ライブラリの細かな選択は対象外

## 7. ローカル開発

### 初回セットアップ

開発ツールのバージョンは [mise](https://mise.jdx.dev/) で管理する。
`.mise.toml` にパッチバージョンまで固定し、ローカルと CI で同じバージョンを使う。

```bash
brew install mise
echo 'eval "$(mise activate zsh)"' >> ~/.zshrc   # 初回のみ。新しいシェルから有効
mise install                                     # Java 21 / Node.js / pnpm を導入
```

### 起動

```bash
docker compose up -d                                          # PostgreSQL / LocalStack
SPRING_PROFILES_ACTIVE=dev ./gradlew :services:quiz-service:bootRun
pnpm --filter web dev
```

**`dev` プロファイルを付けると、デモ用のシードが入る。** 付けないとカテゴリもクイズも空のまま立ち上がる。
シードは Flyway の repeatable マイグレーション（`db/seed/`）で、`dev` のときだけ locations に加わる。

`docker compose up -d` で起動するもの。

| サービス | ポート | 備考 |
| --- | --- | --- |
| PostgreSQL | 5432 | ユーザー / パスワード / DB 名はすべて `quiz` |
| LocalStack | 4566 | S3 / EventBridge / SQS / Secrets Manager / Lambda |

PostgreSQL は本番の Aurora とメジャーバージョンを揃えて 16 系を使う（min 0 ACU は 16.3 以降が前提）。
タイムゾーンは本番との差異を減らすため UTC に固定している。

ローカルの認証情報は開発専用のため、値を直接 `docker-compose.yml` に記載している。

### シードデータ

`dev` プロファイルで起動すると、テナント `demo` に次が入る。
**アプリの仕様ではなくサンプル**であり、スキーマやロジックはこの内容に依存しない。

| 種類 | 内容 |
| --- | --- |
| カテゴリ | AWS / インフラ、認証認可、イベント駆動設計 |
| 難易度 | カテゴリごとに体系が異なる。AWS は同じレベルに SAA / DVA が並ぶ |
| クイズ | 公開 12 問、下書き 1 問 |
| 利用者 | 管理者 1 人、一般ユーザー 1 人 |

同じ内容を何度流しても増えない。repeatable マイグレーションは**内容を変えるたびに再実行される**ため、
識別子を固定して `ON CONFLICT DO NOTHING` で入れている。

### 認証（Phase 1 のスタブ）

Phase 3 までは `X-User-Id` ヘッダの値をそのまま利用者とみなす。
シードで入る利用者の識別子は次のとおり。

| 役割 | `X-User-Id` |
| --- | --- |
| 管理者 | `67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab` |
| 一般ユーザー | `957d085e-3b87-5fa7-9283-5eb6229216b1` |

```bash
curl -H "X-User-Id: 67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab" \
     http://localhost:8080/api/t/demo/admin/categories
```

**ロールと所属はヘッダでは指定できない。** `core.tenant_members` から引く。
認証方式が変わっても認可の仕組みを変えずに済むよう、ロールは Cognito ではなく
アプリケーションのデータとして持つ。ロールを切り替えたいときは所属行を変える。

```sql
UPDATE core.tenant_members SET role = 'member'
WHERE user_id = '67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab';
```

スタブは `prod` / `stg` プロファイルでは無効になり、**それでも有効なら起動に失敗する**。
誰にでもなりすませるため、設定の誤りが全テナントの情報漏洩に直結する。

Phase 3 で差し替えるのは `auth/StubAuthenticator.kt` と `auth/StubAuthenticatorGuard.kt` の削除、
`Authenticator` を実装する Cognito 版の追加だけ。

## 8. コスト方針

個人で運用する規模のため、常時起動のコストを抑えることを前提に設計する。

- NAT Gateway は使わず VPC Endpoint で代替
- Aurora Serverless v2 は **min 0 ACU**（自動一時停止）を採用。一時停止中はストレージ料金のみ
  - 前提: PostgreSQL 16.3 以降 / 無活動時間は 5 分〜24 時間で設定可 / 復帰に約 15 秒
  - **アプリが常時接続を張ると一時停止しない**ため、dev では HikariCP を `minimum-idle: 0` + 短い `idle-timeout` にする
  - 同じ理由で dev では RDS Proxy を使わない
- 通知は Lambda（イベント時のみ課金）で実装し、常駐サービスを増やさない
- 開発環境の ECS タスクは夜間停止（EventBridge Scheduler で desiredCount=0）
