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
| 認証 | Amazon Cognito（Managed Login、パスワード + パスキー） | ロールはアプリのデータで持つ。後で自前実装に差し替える（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)） |
| コンテナ基盤 | ECS Fargate + ALB | Kubernetes は採用しない |
| フロントの配信 | Amplify Hosting | [ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md) |
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
- 「別テナントの ID を指定したアクセスが結果を返さないこと」を、テナント配下の全エンドポイントで検証する。
  `TenantBoundaryApiTest` が全エンドポイントを Spring から列挙し、**検証ケースのないエンドポイントがあると落ちる。**
  エンドポイントを足したら、同じ PR でケースも足す
- `quiz` / `answer` スキーマにテーブルを足したら、`tenant_id` と RLS も付ける。付け忘れると `TenantIsolationTest` が落ちる

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
│       ├── bootstrap/       # tfstate のバケット
│       ├── modules/
│       └── envs/{dev,prod}/
├── tests/
│   └── api/             # API のスモークテスト（Postman / Newman）
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
- **CI の集約ジョブ `ci` が通らないとマージできない。** 個別ジョブを必須にすると、
  パスの出し分けでスキップされたときに報告されず、PR が永久にマージできなくなる
- 「マージ前にブランチを最新にする」は求めていない。PR を 1 本ずつ進めているため。
  並行して開発するようになったら、これより merge queue のほうが待ち時間の面で適切

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
| `/api/me/...` | 認証されていること。テナントを選ぶ前（所属の一覧）や、所属する前（招待の受け入れ）に使う |

パスで判定するため、**テナント配下にエンドポイントを足したときに書き忘れても公開されない。**
コントローラごとに注釈を付ける方式だと、付け忘れがそのまま穴になる。

`/api` 配下は例外なく認証を要求する。テナントの判定より先に確かめるため、
認証していない相手には、テナントがあってもなくても同じ 401 を返す。

テナントの外に置く API は `/api/me` だけ。`TenantBoundaryApiTest` の許可リストに理由とともに載せてある。
**増やすほど境界の外が広がる**ため、足すときは理由と、境界を検証するテストを書く。

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
| `backend` | ktlint / detekt → test（Testcontainers）→ カバレッジの集計 → bootJar → イメージのビルド |
| `frontend` | API クライアントの作り直しに差が出ないか → 型チェック → lint → build → イメージのビルド |
| `terraform` | `terraform fmt -check` → 各ルートモジュールの `validate`。AWS には触れない |
| `secrets` | gitleaks で履歴から secret を探す。パスで出し分けず、常に走る |
| `ci` | 先行ジョブの結果を集約する |
| `deploy-dev` | develop への push で、`ci` が通ったあとに dev へ載せる（`deploy-dev.yml`。[デプロイ](#デプロイ)） |

**Ruleset の必須チェックには `ci` だけを指定する。** ジョブを足すたびに設定を触らずに済み、
パスの出し分けでスキップされたジョブが「報告されないまま待ち続ける」状態にもならない。

ツールのバージョンは CI でも `.mise.toml` から取る。CI 側で別に指定すると二重管理になる。

PR に新しく push すると、動いている古い実行は止まる。develop / main への push では止めない。
develop では最後にデプロイが走り、マイグレーションの途中で止めると、どこまで流れたかが分からなくなる。

### secret の検出

リポジトリは Public のため、**一度 push した secret は、履歴から消しても漏れたものとして扱う。**
[gitleaks](https://github.com/gitleaks/gitleaks) で 2 段に止める。

| いつ | どこで | 見る範囲 |
| --- | --- | --- |
| commit の前 | pre-commit のフック（[lefthook](https://lefthook.dev/)、`lefthook.yml`） | ステージした差分 |
| PR と push | CI の `secrets` ジョブ | HEAD から辿れる履歴すべて |

フックは入れ忘れや `--no-verify` ですり抜けるため、CI でも見る。
どちらも見つけた値はログに出さない（`--redact`）。CI のログは誰でも読める。

フックは clone ごとに 1 回入れる（[初回セットアップ](#初回セットアップ)）。

見つかったとき。

- **commit の前に止まった**: その値をファイルから外し、環境変数や Secrets Manager から読むようにする
- **CI で止まった**: すでに push されている。**まず値を無効にする**（キーの削除、パスワードの変更）。
  force push を禁止しているため、履歴は書き換えない。無効にしたうえで `.gitleaksignore` にフィンガープリントを足す
- **誤検知**: 行末に `gitleaks:allow` を書くか、`.gitleaksignore` にフィンガープリントを足す。どちらも理由を残す

いまは許可リストを持っていない。ローカル専用の認証情報（`docker-compose.yml` の `quiz` など）は、既定のルールに当たらない。

### テスト

```bash
./gradlew :services:quiz-service:test   # レポート: services/quiz-service/build/reports/jacoco/test/html/index.html
```

| 種類 | 対象 | 置き場所の例 |
| --- | --- | --- |
| 単体テスト | ドメインの不変条件、ユースケースの分岐。DB を使わない | `quiz/domain/QuizTest.kt`、`answer/usecase/AttemptUseCaseTest.kt` |
| API テスト | コントローラから DB まで。Testcontainers の PostgreSQL を使う | `quiz/controller/QuizApiTest.kt` |
| 構造のテスト | 規約が守られているか。守られていなければ落ちる | `TenantBoundaryApiTest`、`TenantIsolationTest`、`OpenApiSnapshotTest` |
| スモークテスト | デプロイした環境で、主要な導線が通るか。Newman で流す | `tests/api/` |

**単体テストにするのは、分岐や不変条件を持つものだけ。** リポジトリへ素通しするだけのユースケースには書かない。
SQL が担うこと（絞り込み・並び順・行レベルセキュリティ・連鎖削除）は、フェイクでは確かめられないので API テストで見る。

#### テストデータ

**テストごとに新しいテナントを作り（`TestTenant.create()`）、片付けない。**
テナントの分離がそのままテスト同士の分離になり、別のテストが残した行は見えない。

- 片付けを書かないので、テーブルを足しても各テストの後始末を直さずに済む
- 後始末の書き忘れで次のテストだけが壊れる、という事故も起きない
- テナントの ID と slug は毎回作る。固定すると、テスト同士でぶつかる
- 利用者を区別したいテストは `TestAuth.createUser()` で作る
- テナントをまたいで数える検証（シードの検証など）は、対象のテナントに絞る

#### 単体テストの差し替え

依存は**手書きのフェイク**（`support/fake/`）で差し替える。モックライブラリは使わない。
「どう呼ばれたか」ではなく「結果どうなったか」を確かめるためで、内部の書き換えでテストが壊れにくい。

`TenantTransaction` は本物を使い、内側のトランザクションと DB の設定だけを外す（`fakeTenantTransaction()`）。
**テナントが決まっていなければ失敗する点は本物のまま**にしている。

#### カバレッジ

JaCoCo で計測し、CI のジョブサマリーに出す。**閾値でビルドは落とさない。**
数値を目標にすると、意味の薄いテストが増える。守りたい性質（テナント境界の網羅など）は、構造のテストが担保している。

#### スモークテスト

Postman のコレクション（`tests/api/`）を Newman で流し、**デプロイした環境で主要な導線が通るか**を確かめる。
管理（カテゴリ・難易度・クイズを作る）→ 出題 → 回答 → 結果 → 招待 → テナントの境界 → 片付け、の順に 22 本を呼ぶ。

スモークテストの利用者（`smoke@example.com`、Terraform の `modules/auth` が作る）でアクセストークンを取り、`Authorization` に付けて呼ぶ。
web の proxy は、ログインのセッションが無い要求の `Authorization` をそのまま渡す。ローカルも dev の Cognito の利用者を使う。

```bash
cd infra/terraform/envs/dev
TOKEN=$(AUTH_CLIENT_SECRET="$(terraform output -raw auth_client_secret)" \
  SMOKE_USER_PASSWORD="$(terraform output -raw smoke_user_password)" \
  ../../../../.github/scripts/smoke-token.sh "$(terraform output -raw auth_client_id)" smoke@example.com)
cd -

docker compose up -d
pnpm test:api --env-var accessToken=$TOKEN                                               # web の proxy を通して呼ぶ
pnpm test:api --env-var accessToken=$TOKEN --env-var baseUrl=http://localhost:8080        # API を直接呼ぶ
pnpm test:api --env-var accessToken=$TOKEN --env-var baseUrl=https://develop.<Amplify のアプリの ID>.amplifyapp.com   # dev
```

dev では、デプロイの最後に自動で流れる（[デプロイ](#デプロイ)）。トークンの期限は 1 時間。

**JUnit の API テストと守備範囲を重ねない。** 細かい仕様や境界値は JUnit が見る。
こちらは「つながっているか」（web → API → DB、利用者の識別、マイグレーション）だけを見る。
同じことを両方で検証すると、仕様を変えるたびに 2 か所を直すことになる。

- **専用のテナント `smoke` で動く**（シード `R__smoke_data.sql`）。デプロイのたびに作っては消すので、
  デモのテナントでやるとゴミ箱にたまる。作ったものは最後に消し、途中で失敗しても片付けの段は実行される
- スモークテストの利用者は、シードでメールアドレスだけを登録してある。最初のトークンが届いたときに結び付き、`smoke` テナントの管理者になる（[最初の管理者](#最初の管理者)と同じ仕組み）
- **コレクションは手で書く。** OpenAPI から生成すると、呼ぶ順番と、作った ID を次の要求で使う流れを表せない。
  生成したものに検証を書き足しても、生成し直すと消える
- Postman のアプリでそのまま開ける。書き換えたら、ローカルで流してから commit する
- Newman は `.mise.toml` で固定している（`mise install` で入る）

### OpenAPI

定義は `docs/api/openapi.yaml` にある。**手で書かない。** コードから生成して固定している。

```bash
# API を変えたら再生成してコミットする
UPDATE_OPENAPI=true ./gradlew :services:quiz-service:test --tests '*OpenApiSnapshotTest'
pnpm --filter web generate:api
```

再生成を忘れると `OpenApiSnapshotTest` が落ちる。**落ちること自体が仕組み**なので、
テストを直すのではなく定義を再生成する。

フロントの API クライアント（`apps/web/src/lib/api/generated/`）も生成物で、リポジトリに持つ。
[orval](https://orval.dev/) が定義から TanStack Query のフックと Zod スキーマを作る。
生成し直さないと差分が残るため、CI で検出できる。

**応答は Zod で検証する。** 定義とずれた応答は、画面の奥で `undefined` として壊れる前に
`apps/web/src/lib/api/fetcher.ts` で落ちる。

定義の `required` は、Kotlin のクラスから書き込んでいる（`KotlinRequiredPropertyConverter`）。
**null を許さず、既定値もない引数が必須になる。** springdoc は null 許容しか読まないため、
これがないと応答のすべての項目が省略可能として生成される。

起動中は Swagger UI から定義を読める。

```
http://localhost:8080/swagger-ui.html
```

**環境で出し分けない。** リポジトリが Public で定義もコミットしてある以上、UI を隠しても何も守れない。
「試す」操作も認証を通るため、公開される範囲は API そのものと変わらない。

### コード
- バックエンド: レイヤード（controller / usecase / domain / infrastructure）、テストは JUnit5 + Testcontainers
- フロント: Server Components 優先、API 呼び出しは生成した TanStack Query のフック、応答は Zod で検証
- フォーム: react-hook-form + 生成した Zod スキーマ。**定義に表れない規則だけを足す**（空白だけの入力、公開の条件など）。最終的な判定はバックエンド
- 画面の幅: **360px まで崩さない**（一般的なスマホの下限）。320px でも横スクロールを出さない。
  テーブルは狭い幅で列を減らし、畳んだ列は主となる列の下に小さく出す（`hidden sm:table-cell` と `sm:hidden`）
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
mise install                                     # .mise.toml のツールを導入
mise exec -- lefthook install                    # commit の前に secret を探すフックを入れる
```

### 起動

**ログインは、ローカルでも dev の Cognito を使う**（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)）。
先に `.env` を作り、`AUTH_*` を埋める（書き方は `.env.example`）。無いと、docker compose もアプリも起動しない。
インターネットにつながっていないとログインできない。

```bash
cp .env.example .env
cd infra/terraform/envs/dev
terraform output -raw auth_issuer; terraform output -raw auth_client_id; terraform output -raw auth_client_secret
```

**アプリ一式をコンテナで動かす**（動作確認・デモ向け）。

```bash
docker compose up                                             # http://localhost:3000
```

**アプリをホストで動かす**（開発向け）。依存サービスだけをコンテナで起動する。`.env` をシェルに読み込んでから起動する。

```bash
docker compose up -d postgres localstack
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=dev,migrate ./gradlew :services:quiz-service:bootRun   # マイグレーションとシードを流して終わる
SPRING_PROFILES_ACTIVE=dev ./gradlew :services:quiz-service:bootRun
pnpm --filter web dev                                         # http://localhost:3000
```

**アプリは起動時にマイグレーションしない。** マイグレーションを足したら、2 行目を流し直す（[マイグレーション](#マイグレーション)）。

2 つを同時に動かすとポートが重なる。ホスト側のポートは `.env` で変えられる（`.env.example` を参照）。

フロントは `/api` をバックエンドへ中継する（`src/proxy.ts`）。ブラウザからは同一オリジンに見えるため、
CORS の設定は要らない。中継先は `API_ORIGIN` で変えられる（既定は `http://localhost:8080`）。
**中継先は実行時に読む。** `next.config.ts` の rewrites はビルド時に固定されるため使わない。
同じイメージを環境ごとに使い回すため。
AWS では Amplify Hosting がソースからビルドする。SSR の実行時には環境変数が渡らないため、
ビルドの中で `.env.production` に書き出す（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）。

### コンテナイメージ

| イメージ | Dockerfile | 備考 |
| --- | --- | --- |
| quiz-service | `services/quiz-service/Dockerfile` | 依存・ローダー・アプリを層に分けて置く。コードだけの変更で依存の層を送り直さない |
| web | `apps/web/Dockerfile` | Next.js の standalone 出力。`node_modules` を丸ごと持たない |

どちらもビルドコンテキストはリポジトリのルートで、root 以外の利用者で動く。
quiz-service は Phase 2 の ECS でも同じイメージを使い、環境の違いは環境変数で渡す（[ECS](#ecsquiz-service)）。
AWS では arm64（Graviton）で動かす。Mac（Apple シリコン）でビルドしたイメージがそのまま使える。
web のイメージはローカル用。AWS では Amplify Hosting がソースからビルドする。

ヘルスチェックは `GET /actuator/health`。**DB には問い合わせない。**
ALB が定期的に叩くと、Aurora Serverless v2 の自動一時停止（min 0 ACU）が発動しなくなるため。

**`dev,migrate` で流すと、デモ用のシードが入る。** `dev` を付けないとカテゴリもクイズも空のまま立ち上がる。
シードは Flyway の repeatable マイグレーション（`db/seed/`）で、`dev` のときだけ locations に加わる。

`docker compose up` で起動するもの。

| サービス | ポート | 備考 |
| --- | --- | --- |
| web | 3000 | Next.js |
| quiz-service | 8080 | `dev` プロファイル。migrate が成功してから起動する |
| migrate | なし | quiz-service と同じイメージを `dev,migrate` で起動する。マイグレーションとシードを流して終了する |
| PostgreSQL | 5432 | ユーザー / パスワード / DB 名はすべて `quiz` |
| LocalStack | 4566 | S3 / EventBridge / SQS / Secrets Manager / Lambda |

PostgreSQL は本番の Aurora とメジャーバージョンを揃えて 16 系を使う（min 0 ACU は 16.3 以降が前提）。
タイムゾーンは本番との差異を減らすため UTC に固定している。

ローカルの認証情報は開発専用のため、値を直接 `docker-compose.yml` に記載している。

### マイグレーション

**アプリは起動時にマイグレーションしない。** スキーマ所有者（DDL の権限）の認証情報をアプリに持たせないため。
同じイメージを `migrate` プロファイルで起動すると、マイグレーションだけを流して終了する。HTTP は受けない。

| 起動するもの | プロファイル | 接続するロール |
| --- | --- | --- |
| アプリ | `dev` / `prod` など | `quiz_app`（読み書きのみ。行レベルセキュリティが効く） |
| マイグレーション | `migrate`。シードも流すなら `dev,migrate` | `quiz`（スキーマ所有者） |

AWS では、デプロイのたびに、サービスを入れ替える前に ECS の単発タスクとして流す。
失敗すると終了コードが 0 以外になり、デプロイはそこで止まる。
AWS ではパスワードを使わず、IAM 認証で接続する（[Aurora](#aurora)）。
所有者（`quiz`）として接続する権限（`rds-db:connect`）は、この単発タスクのロールにだけ与える。
AWS の dev はデモに使うため `dev,migrate` でシードも流す。本番で `dev` を付けると `SeedDataGuard` が止める。

見送った方式。

- **アプリの起動時に流す**: アプリが DDL の権限を持ち続ける。タスクが複数あると、起動のたびにそれぞれが流そうとする
- **GitHub Actions から流す**: Runner からプライベートサブネットの Aurora へ届く経路が要る

**マイグレーションは、1 つ前のアプリと両立させる。**
デプロイ中は新旧のタスクが同時に動き、マイグレーションは新しいタスクより先に流れる。
古いタスクが使っている列を消したり名前を変えたりすると、入れ替わるまでの間、古いタスクが壊れる。

- 列やテーブルの追加は 1 回で行う。`NOT NULL` の列には既定値を付ける。古いタスクはその列を知らずに `INSERT` する
- 削除と名前の変更は 2 回に分ける。先にアプリが使わないようにしてリリースし、次のリリースで消す

**テナント配下の行を入れるマイグレーションは、先に `app.tenant_id` を設定する**（`set_config('app.tenant_id', ..., true)`。シードを参照）。
Aurora の `quiz` はスーパーユーザーではなく、`FORCE ROW LEVEL SECURITY` によって所有者にもポリシーが効く。
ローカルとテストの `quiz` はスーパーユーザーで RLS を素通りするため、設定を忘れても手元では気づけない。
`DemoSeedTest` は、RLS の対象になる `quiz_app` でシードを流して確かめている。

テストだけは、コンテキストの起動時に流す（`TestPostgres`）。手順を 1 つにするため。
「アプリは流さない」「migrate は流して HTTP を受けない」は `MigrationProfileTest` が確かめる。

### シードデータ

`dev,migrate` で流すと、次が入る。
**アプリの仕様ではなくサンプル**であり、スキーマやロジックはこの内容に依存しない。

| 種類 | `demo`（デモ） | `geo-club`（地理の勉強会） |
| --- | --- | --- |
| カテゴリ | AWS / インフラ、認証認可、イベント駆動設計 | 世界の首都 |
| 難易度 | カテゴリごとに体系が異なる。AWS は同じレベルに SAA / DVA が並ぶ | 1 つ |
| クイズ | 公開 12 問、下書き 1 問 | 公開 3 問 |

2 つ目のテナントは、テナントの選択と、テナントごとにロールが違うことを見せるためにある。
利用者は所属の数が 0 / 1 / 2 の 3 人で、`/` の出し分けをすべて試せる（[最初の管理者](#最初の管理者)の表）。
**シードの利用者のままではログインできない。** 使うときは、自分のメールアドレスを割り当てる（[最初の管理者](#最初の管理者)）。

ほかに、スモークテスト専用のテナント `smoke` と、その管理者が入る（`R__smoke_data.sql`）。
管理者はメールアドレス（`smoke@example.com`）だけで登録してあり、同じアドレスの Cognito の利用者（Terraform が作る）が最初にログインしたときに結び付く。
カテゴリやクイズはテストが作って消すため、シードでは入れない。

同じ内容を何度流しても増えない。repeatable マイグレーションは**内容を変えるたびに再実行される**ため、
識別子を固定して `ON CONFLICT DO NOTHING` で入れている。

### 認証

ログインは Cognito の Managed Login で、パスワードとパスキーを使う（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)）。

```
ブラウザ → web（/auth/login）→ Managed Login → web（/auth/callback）→ トークンを暗号化して HttpOnly の Cookie に置く
ブラウザ → web の proxy（Cookie からアクセストークンを取り出して Authorization に付ける）→ quiz-service（JWT を検証する）
```

**アプリの利用者の ID は Cognito から切り離す。** `core.users.id` がアプリの ID で、Cognito の `sub` は `external_id` に入る。
初めてのアクセストークンが届いたとき、バックエンドが利用者を作り、確認済みのメールアドレスを Cognito の `GetUser` で取って持つ。
OIDC の userinfo は使わない。API で取ったトークン（スモークテスト）は `openid` のスコープを持てず、userinfo が受け付けない。
認証の方式を替えても、回答の履歴と所属が残る。

**ロールと所属はトークンでは決まらない。** `core.tenant_members` から引く。ロールを切り替えたいときは所属行を変える。

```sql
UPDATE core.tenant_members SET role = 'member'
WHERE user_id = '67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab';
```

バックエンドが確かめるのは、署名（発行者の JWKS）、発行者、期限、アクセストークンであること（`token_use`）、
web のクライアントに発行されたこと（`client_id`）。Spring Security のリソースサーバーは使わず、JWT の検証の部品だけを使う。
フィルタが前に立つと、401 の応答がアプリの形（RFC 9457）にならず、認可の判定も二重になるため（`auth/AccessTokens.kt`）。

同じメールアドレスの利用者が、すでに別の Cognito の利用者に結び付いているときは、401 で拒否する（Cognito で利用者を作り直したときなど）。
別の利用者として作ると、所属と履歴が黙って分かれる。結び直すときは `external_id` を書き換える。

#### 最初の管理者

管理者がいないテナントに、最初の管理者を入れる手順。**メールアドレスだけを持つ利用者に、所属を付けておく。**
招待は招待した管理者を記録するため、管理者のいないテナントでは作れない。
ADR-0016 の「運用の手順で入る」は、この事前の登録で行う。2 人目からは、この人が画面から招待する（[招待](#招待)）。
同じアドレスで Cognito にサインアップし、最初にログインしたときに結び付く。

シードのデモ管理者を自分にする例（ローカルは `docker compose exec postgres psql -U quiz`、AWS は [Data API](#aurora)）。

```sql
UPDATE core.users SET email = '<自分のメールアドレス>', external_id = NULL
WHERE id = '67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab';
```

すでにログインしたあと（自分の利用者ができている）なら、自分の利用者に所属を足す。

```sql
INSERT INTO core.tenant_members (tenant_id, user_id, role)
SELECT t.id, u.id, 'admin' FROM core.tenants t, core.users u
WHERE t.slug = 'demo' AND lower(u.email) = lower('<自分のメールアドレス>');
```

| 利用者 | ID | 所属 |
| --- | --- | --- |
| デモ管理者 | `67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab` | `demo`（管理者）、`geo-club`（一般ユーザー） |
| デモ利用者 | `957d085e-3b87-5fa7-9283-5eb6229216b1` | `demo`（一般ユーザー） |
| デモ未所属 | `7918a5c2-30ee-56c8-b76c-57c6a79774e3` | なし |

#### 招待

管理者が管理画面の「招待」で、メールアドレスとロールを指定してリンクを作り、相手に渡す。**メールは送らない。**
相手はリンク（`/invitations/{token}`）を開き、ログイン（はじめてならサインアップ）してから「参加する」を押す。
扱いの決まりは[要件定義](requirements.md#招待フローphase-3)にある。

- **受け入れるには、ログインした人の確認済みのメールアドレスが、招待したアドレスと一致しなければならない。** リンクを持っているだけでは入れない
- **リンクは作った直後にしか表示できない。** DB にはトークンのハッシュしか持たない。なくしたら、同じアドレスへ招待し直す
- 受け入れの API（`/api/me/invitations/{token}`）はテナントの外にある。受け入れる人は、まだ所属していないため。
  範囲はトークンと本人のメールアドレスで絞り、境界の検証は `InvitationApiTest` が担う
- 別のアドレスで開いた人には、招待先のテナントも、招待したアドレスも返さない

#### フロントエンド

トークンは web のサーバーが受け取り、暗号化して `HttpOnly` の Cookie に置く（`src/lib/auth/session.ts`）。**ブラウザの JavaScript からは読めない。**
`Authorization` を付けるのは Next.js の proxy（`src/proxy.ts`）だけで、期限が近ければリフレッシュトークンで更新する。
画面のコードは認証を意識せずに API を呼ぶ。

- ログインの始まり（`/auth/login`）で、`state` と PKCE の verifier を暗号化した Cookie に置き、コールバックで照合する
- ログアウト（`/auth/logout`）は POST だけを受ける。リフレッシュトークンを失効させ、Cognito のセッションも終える
- ログインのセッションが無い要求は、`Authorization` をそのまま渡す。スモークテストのように、トークンを自分で取る呼び出し元のため
- AWS では、proxy が秘密のヘッダ（`X-Origin-Verify`）も付ける。値は環境変数 `ORIGIN_VERIFY_SECRET` から読み、ローカルでは付けない（[Amplify](#amplifyweb)）

`/` は所属テナントの数で出し分ける（0 件: 招待を受けていない旨 / 1 件: そのテナントへ / 2 件以上: 選択画面）。
所属の一覧はブラウザから取る。サーバーで取ると、proxy 以外でもトークンを付けることになる。

**利用者が替わると、TanStack Query のキャッシュを作り直す**（ルートレイアウトで `Providers` に利用者をキーとして渡す）。
残すと、前の利用者の応答が次の利用者の画面に出る。

### インフラ（Terraform）

AWS のリソースは Terraform で作る。コンソールで直接変えない。
state の置き場所と環境の分け方は [ADR-0011](adr/0011-terraform-state-and-environments.md) を参照。

| ディレクトリ | 内容 | state のキー |
| --- | --- | --- |
| `infra/terraform/bootstrap` | tfstate のバケット | `bootstrap/terraform.tfstate` |
| `infra/terraform/account` | アカウントに 1 つだけ置くもの（予算、コスト配分タグ、GitHub Actions の OIDC プロバイダ） | `account/terraform.tfstate` |
| `infra/terraform/envs/dev` | dev 環境 | `dev/terraform.tfstate` |
| `infra/terraform/envs/prod` | prod 環境 | `prod/terraform.tfstate` |
| `infra/terraform/modules` | 環境で共有する部品 | — |

```bash
aws sso login --profile quiz-app-admin
export AWS_PROFILE=quiz-app-admin

cd infra/terraform/envs/dev
terraform init
terraform plan
terraform apply
```

**apply はローカルから行う。** CI は整形と `validate` だけで、Terraform からは AWS に触れない。
デプロイに使うロールにも、Terraform を動かす権限は与えていない（[ADR-0015](adr/0015-deploy-by-registering-task-definitions-from-ci.md)）。

- 同時に操作すると、あとから始めたほうがロックで止まる（`Error acquiring the state lock`）。
  ロックは S3 上の `*.tflock` で、異常終了で残ったときは `terraform force-unlock <ID>` で外す
- **apply の途中で SSO の認証が切れると、state を S3 に書けない。** 作ったリソースは手元の `errored.tfstate` にだけ記録され、ロックも残る。
  `terraform force-unlock <ID>` のあと `terraform state push errored.tfstate` で戻す。
  認証の有効期限は既定で 1 時間のため、長くかかる apply の前に `aws sso login` し直す
- `-target` は、指定したリソースの依存もたどって巻き込む。ほかに保留中の変更があると、その一部だけが流れて途中で止まることがある。
  plan が意図したリソースだけであることを確かめてから承認する
- provider のバージョンは `.terraform.lock.hcl` で固定している。上げるときは
  `terraform init -upgrade` のあと、`terraform providers lock -platform=darwin_arm64 -platform=linux_amd64`
  で CI（Linux）の分も記録する
- 全リソースに `Project` / `Env` / `ManagedBy` のタグが付く（`default_tags`）

#### Aurora

`modules/database` で作る。ローカルと同じく、スキーマの所有者とアプリの接続先を分ける。
**アプリとマイグレーションはパスワードを持たず、IAM 認証で接続する**（[ADR-0014](adr/0014-connect-to-aurora-with-iam-auth.md)）。

| ロール | 使う者 | 認証 |
| --- | --- | --- |
| `quiz_admin`（マスター） | ロールの作成だけ | パスワード。RDS が作り、Secrets Manager で管理する |
| `quiz` | マイグレーションの単発タスク | IAM 認証 |
| `quiz_app` | quiz-service | IAM 認証 |

`quiz` と `quiz_app` は、apply のときに `sql/bootstrap_roles.sql` を Data API で流して作る。
**apply する環境に AWS CLI が要る。**

Aurora はプライベートサブネットにあり、手元からは直接届かない。SQL を流すときも Data API を使う。
マスターとして流れるため、**RLS は効かない。** 確認のための読み取りにとどめる。

```bash
cd infra/terraform/envs/dev
aws rds-data execute-statement \
  --resource-arn "$(terraform output -raw database_cluster_arn)" \
  --secret-arn "$(terraform output -raw database_master_user_secret_arn)" \
  --database quiz \
  --sql "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname LIKE 'quiz%'"
```

一時停止している間は `DatabaseResumingException` が返る。十数秒おいてやり直す。

#### ドメイン

Route 53 に登録済みのドメインを使う。**ドメイン名はリポジトリに書かず、`terraform.tfvars` の `domain_name` に置く**
（Git の管理外。書き方は `terraform.tfvars.example`）。

| 環境 | web | API |
| --- | --- | --- |
| dev | `dev.<ドメイン>`（Amplify） | `api.dev.<ドメイン>` |

ホストゾーンはドメインの登録時に作られ、環境をまたいで使う。Terraform では作らず、参照してレコードを足すだけにする。
ドメインの apex（`<ドメイン>`）には、このアプリ以外の既存のレコードがある。触らない。

#### 認証（Cognito）

`modules/auth` で作る（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)）。

- User Pool は Essentials。サインインはパスワードとパスキーで、画面は Managed Login（Cognito のプレフィックスドメイン）
- パスキーは、一度パスワードでサインインしてから登録する。サインアップの直後は、Managed Login が登録を勧める
- 誰でもサインアップできる。所属とロールはアプリの DB にあり、所属がなければどのテナントのデータにも触れられない
- メールは Cognito の既定の設定（1 日 50 通まで）。確認コードとパスワードの再設定にだけ使う
- コールバックを許すのは `dev.<ドメイン>` と `http://localhost:3000` だけ。**Amplify の既定のドメインからはログインできない**
- web のクライアントのシークレットと、Cookie の暗号鍵は、Amplify の環境変数（`AUTH_*`）に入る
- web のクライアントのスコープは `openid email aws.cognito.signin.user.admin`。最後のものは、バックエンドが確認済みのメールアドレスを取る（`GetUser`）ために要る。
  このスコープのトークンは自分の属性を書き換えられるが、トークンはブラウザに渡らない
- スモークテストの利用者（`smoke@example.com`）も Terraform が作る。パスワードは `terraform output -raw smoke_user_password`

Managed Login の画面は、web をつながなくても開ける。ログインのあとは `redirect_uri` に戻る（開いていなければエラーの画面になるが、ログインはできている）。

```bash
cd infra/terraform/envs/dev
echo "$(terraform output -raw auth_managed_login_url)/login?client_id=$(terraform output -raw auth_client_id)&response_type=code&scope=openid+email&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fauth%2Fcallback"
```

#### Amplify（web）

`modules/web` で作る。ビルドの手順はリポジトリのルートの `amplify.yml` にある（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）。

```
ブラウザ → Amplify（dev.<ドメイン>）→ SSR の proxy（アクセストークンを付ける）→ ALB（秘密のヘッダ）→ quiz-service
```

- **画面にベーシック認証はかけない。** データはログインとテナントの所属で守られる（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)）。
  スタブ認証の間は、誰にでもなりすませるためかけていた
- SSR の実行時には Amplify の環境変数が渡らない。`amplify.yml` がビルドの中で、サーバー側で読む値（`API_ORIGIN`、`ORIGIN_VERIFY_SECRET`、`AUTH_*`）だけを `.env.production` に書き出す。
  `NEXT_PUBLIC_` を付けないので、ブラウザ向けのコードには入らない
- pnpm は、ビルドの中でだけ `nodeLinker: hoisted` にする。既定の配置では Amplify が `next` を見つけられない
- **push でビルドしない。** デプロイのワークフローが、quiz-service の後に起動する（[デプロイ](#デプロイ)）。ビルドは約 3 分。
  手で起動するときは次のコマンドを使う

```bash
cd infra/terraform/envs/dev
aws amplify start-job --app-id "$(terraform output -raw web_amplify_app_id)" \
  --branch-name develop --job-type RELEASE
```

Cookie の暗号鍵を入れ替えるときは `terraform apply -replace=module.web.random_password.session` のあと、ビルドし直す。ログイン中の全員がログアウトされる。

**GitHub のトークンは、アプリを作るときにだけ渡す。** 接続に `admin:repo_hook` の権限が 1 回だけ要る。
ファイルには書かず、環境変数で渡し、作成後はすぐに失効させる。state には残るが、失効していれば使えない。

```bash
TF_VAR_github_access_token=<トークン> terraform apply
```

秘密のヘッダの値を入れ替えるときは、ALB と web を続けて更新する。間は web から API に届かない。

```bash
terraform apply -replace=module.quiz_service.random_password.origin_verify
aws amplify start-job ...   # 新しい値でビルドし直す
```

#### ECS（quiz-service）

`modules/quiz-service` で作る。

```
インターネット → ALB（HTTPS、api.dev.<ドメイン>）→ quiz-service（Fargate / arm64 / 0.5 vCPU・1 GB）→ Aurora（IAM 認証）
```

- **ALB は秘密のヘッダ（`X-Origin-Verify`）を持たない要求を 403 で返す。** API を web の proxy 以外から呼ばせない。
  ヘッダを付けるのは web（Amplify）の proxy だけで、値は Secrets Manager にある（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）。
  ALB をやめるとき（DEV-56）に見直す
- アクセストークンの発行者と web のクライアントは、タスク定義の環境変数（`AUTH_ISSUER`、`AUTH_CLIENT_ID`）で渡す。マイグレーションのタスクにも渡す。無いと起動しない
- **ALB は HTTPS だけを受ける。HTTP のリスナーは置かず、リダイレクトもしない。**
  API を呼ぶのは web の proxy だけで、HTTP で届いた時点でヘッダが平文で流れてしまうため。
  web（Amplify）の実行環境は送信元の IP が決まらないので、受信は IP では絞らず、ヘッダで決める
- TLS は 1.2 / 1.3 だけを許す（`ELBSecurityPolicy-TLS13-1-2-Res-PQ-2025-09`）。証明書は ACM が DNS 検証で自動更新する
- アプリは起動時に DB へつながない（`spring.data.jdbc.dialect`）。マイグレーションのタスクは `quiz_app` に接続できず、
  アプリも起動のたびに一時停止中の Aurora を起こさずに済む
- タスクはパブリックサブネットに置き、パブリック IP から ECR や CloudWatch Logs へ出る（[ADR-0013](adr/0013-run-ecs-tasks-in-public-subnets.md)）。
  受信は ALB からだけ
- DB へは AWS Advanced JDBC Wrapper の `iam` プラグインで接続する。接続先の URL が `jdbc:aws-wrapper:postgresql:` のときだけ使われ、
  ローカルは素の PostgreSQL ドライバのまま。違いはタスク定義の環境変数だけにある
- ログは CloudWatch Logs の `/ecs/quiz-app-dev/quiz-service`（`app/` と `migrate/`）。14 日で消える
- 起動に失敗したら、前のタスク定義に自動で戻る（デプロイサーキットブレーカー）
- **深夜（2:00〜8:00、日本時間）は止める。** EventBridge Scheduler がタスクの数を 0 にし、朝に 1 に戻す（`schedule.tf`）。
  止まっている間、API は ALB が 503 を返し、画面には「サーバーが止まっているか、起動の途中です」と出る。
  タスクの数は Terraform では無視している。夜に apply しても起動しない

止まっている間に使うときは、手で起動する。次の停止の時刻（2:00）にまた止まる。

```bash
aws ecs update-service --cluster quiz-app-dev --service quiz-service --desired-count 1
```

**デプロイは GitHub Actions が行う**（[デプロイ](#デプロイ)）。Terraform が持つのはタスク定義の形（環境変数、ロール、CPU など）までで、
どのイメージを動かすかは持たない。サービスが参照するリビジョンの変化は、Terraform では無視している。

動作を確かめるときは、秘密のヘッダとアクセストークンを付けて呼ぶ。トークンはスモークテストの利用者で取る（[スモークテスト](#スモークテスト)）。

```bash
SECRET=$(aws secretsmanager get-secret-value \
  --secret-id "$(terraform output -raw origin_header_secret_arn)" --query SecretString --output text)
curl -H "X-Origin-Verify: $SECRET" -H "Authorization: Bearer $TOKEN" \
     "$(terraform output -raw quiz_service_url)/api/t/smoke/admin/categories"
```

#### デプロイ

develop にマージすると、CI（`ci.yml`）のチェックが通ったあとに、`deploy-dev.yml` が dev に載せる
（[ADR-0015](adr/0015-deploy-by-registering-task-definitions-from-ci.md)）。

```
イメージを作る（arm64）→ ECR に push → マイグレーション（単発タスク）→ サービスの入れ替え → web のビルド（Amplify）→ スモークテスト
```

- **変わったほうだけを載せる。** quiz-service は `backend`、web は `frontend` の変更で動く（`changes` の判定）。
  ドキュメントだけの変更では動かない
- **マイグレーションが失敗したら、サービスは替えない。** 入れ替えで起動に失敗し、前のリビジョンに戻ったときも失敗にする
- タスク定義は、ファミリーの最新のリビジョンからイメージだけを差し替えて登録する。
  **Terraform で形（環境変数など）を変えたら、デプロイを手で流して反映する**
- 後から始まったデプロイは、先のものが終わるまで待つ。途中で止めない
- **夜間の停止中にデプロイすると、1 つ起動してから載せる。** 止めたままでは、スモークテストで確かめられないため。
  次の停止の時刻にまた止まる
- 失敗すると GitHub から通知が届く。マイグレーションのログは CloudWatch Logs の `migrate/quiz-service/<タスク ID>` にある
- イメージのタグはコミットの SHA（先頭 12 桁）。ECR には直近 10 個が残る
- マージから載り終えるまで約 9 分（イメージ 2 分、quiz-service 5 分、web 2 分）。起動に失敗して前のリビジョンに戻るときは、失敗が分かるまで 15 分ほどかかる

手で流すとき（形を変えたあと、失敗をやり直すときなど）。

```bash
gh workflow run deploy-dev.yml --ref develop                    # quiz-service と web
gh workflow run deploy-dev.yml --ref develop -f frontend=false   # quiz-service だけ
```

GitHub Actions が使えないときは、手元から同じスクリプトで流せる。

```bash
cd infra/terraform/envs/dev
REPO=$(terraform output -raw quiz_service_ecr_repository_url)
TAG=$(git rev-parse --short=12 HEAD)

aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
docker build --platform linux/arm64 -f ../../../../services/quiz-service/Dockerfile -t "$REPO:$TAG" ../../../..
docker push "$REPO:$TAG"
../../../../.github/scripts/deploy-quiz-service.sh quiz-app-dev quiz-service quiz-app-dev-quiz-service-migrate "$REPO:$TAG"
```

**GitHub Actions は OIDC でロールを引き受ける。アクセスキーは使わない**（`modules/deploy-role`）。

- 引き受けられるのは、このリポジトリの Environment `dev` で動くジョブだけ。`dev` は develop からしか使えない（GitHub の設定）
- ロールにできるのは、ECR への push、2 つのタスク定義の登録、マイグレーションの起動、サービスの更新、Amplify のビルドの起動だけ
- OIDC のプロバイダはアカウントに 1 つだけ作れる。`infra/terraform/account` に置いている

Environment `dev` には、次を置く。値は Terraform の出力から入れる。

| 名前 | 種類 | 値 |
| --- | --- | --- |
| `AWS_ROLE_ARN` | secret | 引き受けるロール |
| `AUTH_CLIENT_SECRET` | secret | web のクライアントのシークレット。スモークテストがトークンを取るのに使う |
| `SMOKE_USER_PASSWORD` | secret | スモークテストの利用者のパスワード |
| `AMPLIFY_APP_ID` | variable | ビルドを起動する Amplify のアプリ |
| `AUTH_CLIENT_ID` | variable | web のクライアント |

```bash
cd infra/terraform/envs/dev
gh secret set AWS_ROLE_ARN --env dev --body "$(terraform output -raw deploy_role_arn)"
gh secret set AUTH_CLIENT_SECRET --env dev --body "$(terraform output -raw auth_client_secret)"
gh secret set SMOKE_USER_PASSWORD --env dev --body "$(terraform output -raw smoke_user_password)"
gh variable set AMPLIFY_APP_ID --env dev --body "$(terraform output -raw web_amplify_app_id)"
gh variable set AUTH_CLIENT_ID --env dev --body "$(terraform output -raw auth_client_id)"
```

クライアントのシークレットやスモークテストの利用者を作り直したら、secret も入れ替える。

**環境を作り直すときは、先にイメージを push し、そのタグを `quiz_service_image_tag` に入れて apply する。**
この値はサービスを作るときにだけ使う。以降は変えても、動くタスクは替わらない。

#### state のバケットを作り直すとき

通常は触らない。バケットごと失った場合だけ、次の順で作る。

1. `bootstrap/backend.tf` を一時的に外し、ローカル state で `terraform init` → `apply`
2. `backend.tf` を戻し、`terraform init -migrate-state` で state をバケットへ移す

## 8. コスト方針

個人で運用する規模のため、常時起動のコストを抑えることを前提に設計する。

- NAT Gateway も VPC Endpoint も使わず、ECS のタスクをパブリックサブネットに置く（[ADR-0013](adr/0013-run-ecs-tasks-in-public-subnets.md)）
  - どちらもタスクを止めても課金が続き、Endpoint は必要な本数 × AZ 数で NAT Gateway より高くなる
  - タスクへの受信は SecurityGroup の参照だけで許し、CIDR では開けない。外からの入口は ALB だけ
- Aurora Serverless v2 は **min 0 ACU**（自動一時停止）を採用。一時停止中はストレージ料金のみ
  - 前提: PostgreSQL 16.3 以降 / 無活動時間は 5 分〜24 時間で設定可（dev は 5 分）/ 復帰に約 15 秒
  - **アプリが常時接続を張ると一時停止しない**ため、dev では HikariCP を `minimum-idle: 0` + 短い `idle-timeout` にする
  - 同じ理由で dev では RDS Proxy を使わない
  - **AWS Advanced JDBC Wrapper は `wrapperDialect=pg` にする。** Aurora と判定させると、クラスタの構成を見張る接続を
    プールとは別に張り、最後の利用から 15 分ほど保ち続ける。その間は一時停止しない
  - 実測（DEV-50）: 最後の接続が切れてから 5 分で一時停止する。止まっている DB への最初の要求は、復帰（約 13 秒）を待って
    約 19 秒で 200 が返る。2 回目からは通常の速さ
  - マイグレーション（Flyway）も、止まっている DB を起こす。接続を試し直す回数を 6 回にしている（`application-migrate.yml`）。
    既定の 0 回では、復帰の途中で失敗する
  - 復帰を待つのはバックエンド。接続プールの `connection-timeout` を、復帰にかかる時間より長くしている（45 秒）。
    プールを作るときの接続の試み（`initialization-fail-timeout`）は外している。デプロイで替わったタスクが止まっている DB に初めてつなぐと、
    既定では 1 回の失敗ですぐに 500 を返す
    24 時間を超えて止まっていると復帰に 30 秒を超えることがある。それでも間に合わなかった読み込みは、
    画面の再試行（5xx と通信エラーを 2 回まで、`src/app/providers.tsx`）で拾う。止まったあとの最初の操作は、たいてい読み込み
  - 一時停止しないときは、`DatabaseConnections` と RDS のイベント（クラスタ単位の「Initiated pause / resume」）を見る。
    接続の中身は Data API で `pg_stat_activity` を読むと分かる。`rdsadmin` の接続は一時停止を妨げない
- 通知は Lambda（イベント時のみ課金）で実装し、常駐サービスを増やさない
- 開発環境の ECS タスクは深夜（2:00〜8:00）に止める（[ECS](#ecsquiz-service)）。タスクとそのパブリック IP の費用が、月に約 5 ドル減る
  - Lambda は挟まず、EventBridge Scheduler から ECS の UpdateService を直接呼ぶ。タスクの数を変えるだけで、判断することがない
  - 止まっている間にデプロイしたときは、デプロイが起動する。夜に作業しているなら、動いていてほしいため

止めても課金が続くものがある。dev の月額の目安（東京リージョン、1 か月 730 時間）。

| 費用 | 月額 | 深夜の停止 |
| --- | --- | --- |
| ALB | 約 17.7 ドル（1 時間 0.0243 ドル） | 続く |
| ALB のパブリック IPv4（2 つの AZ に 1 つずつ） | 約 7.3 ドル（1 時間 1 つ 0.005 ドル） | 続く |
| Secrets Manager（秘密のヘッダ、Aurora のマスター） | 0.8 ドル | 続く |
| Route 53 のホストゾーン | 0.5 ドル。このアプリ以外のレコードと共有 | 続く |
| Aurora のストレージ、ECR のイメージ | GB あたり 0.12 ドル / 0.10 ドル。どちらも数 GB 以下 | 続く |
| ECS のタスク（0.5 vCPU / 1 GB） | 約 13.5 ドル（1 時間 0.0246 ドル × 1 日 18 時間） | 止まる |
| タスクのパブリック IPv4 | 約 2.7 ドル | 止まる |
| Aurora の ACU | 使った分だけ（1 ACU 時 0.15 ドル） | 一時停止すれば 0 |
| Amplify | ビルド（1 分 0.01 ドル）と SSR の実行。使った分だけ | — |

**止められない費用の大半は ALB で、パブリック IPv4 を含めて月に約 25 ドルかかる。** 使っていなくても減らない。
- **予算は月 30 ドル。** 実績が 85% と 100% を超えたとき、月末の予測が 100% を超えたときにメールで届く
  （`infra/terraform/account`）。通知先は公開リポジトリに載せないため、`terraform.tfvars`（Git の管理外）で渡す
  - 予測でも知らせるのは、月の途中で止める判断をするため。実績だけだと、気づいたときには超えている
  - クレジットと返金は差し引かずに数える。相殺されて、使った量が見えなくなるのを避ける
- 全リソースに付けている `Project` / `Env` のタグで、Cost Explorer から環境ごとの費用を見る（コスト配分タグ）
