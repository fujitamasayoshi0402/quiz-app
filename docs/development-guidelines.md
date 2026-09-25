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
| `/api/me/...` | 認証されていること。テナントを選ぶ前に使う |

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

**Ruleset の必須チェックには `ci` だけを指定する。** ジョブを足すたびに設定を触らずに済み、
パスの出し分けでスキップされたジョブが「報告されないまま待ち続ける」状態にもならない。

ツールのバージョンは CI でも `.mise.toml` から取る。CI 側で別に指定すると二重管理になる。

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
管理（カテゴリ・難易度・クイズを作る）→ 出題 → 回答 → 結果 → テナントの境界 → 片付け、の順に 18 本を呼ぶ。

```bash
docker compose up -d
pnpm test:api                                              # web の proxy を通して呼ぶ
pnpm test:api --env-var baseUrl=http://localhost:8080      # API を直接呼ぶ
```

**JUnit の API テストと守備範囲を重ねない。** 細かい仕様や境界値は JUnit が見る。
こちらは「つながっているか」（web → API → DB、利用者の識別、マイグレーション）だけを見る。
同じことを両方で検証すると、仕様を変えるたびに 2 か所を直すことになる。

- **専用のテナント `smoke` で動く**（シード `R__smoke_data.sql`）。デプロイのたびに作っては消すので、
  デモのテナントでやるとゴミ箱にたまる。作ったものは最後に消し、途中で失敗しても片付けの段は実行される
- 利用者の識別は、`X-User-Id` と Cookie の両方を付ける。web を通すと Cookie、API を直接呼ぶと `X-User-Id` が使われる
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

**アプリ一式をコンテナで動かす**（動作確認・デモ向け）。

```bash
docker compose up                                             # http://localhost:3000
```

**アプリをホストで動かす**（開発向け）。依存サービスだけをコンテナで起動する。

```bash
docker compose up -d postgres localstack
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
利用者は所属の数が 0 / 1 / 2 の 3 人で、`/` の出し分けをすべて試せる（下の表）。

ほかに、スモークテスト専用のテナント `smoke` と、その管理者が入る（`R__smoke_data.sql`）。
カテゴリやクイズはテストが作って消すため、シードでは入れない。ログイン画面にも出さない。

同じ内容を何度流しても増えない。repeatable マイグレーションは**内容を変えるたびに再実行される**ため、
識別子を固定して `ON CONFLICT DO NOTHING` で入れている。

### 認証（Phase 1 のスタブ）

Phase 3 までは `X-User-Id` ヘッダの値をそのまま利用者とみなす。
シードで入る利用者の識別子は次のとおり。

| 利用者 | `X-User-Id` | 所属 |
| --- | --- | --- |
| デモ管理者 | `67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab` | `demo`（管理者）、`geo-club`（一般ユーザー） |
| デモ利用者 | `957d085e-3b87-5fa7-9283-5eb6229216b1` | `demo`（一般ユーザー） |
| デモ未所属 | `7918a5c2-30ee-56c8-b76c-57c6a79774e3` | なし |

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

#### フロントエンド

`http://localhost:3000/login` で利用者を選ぶと、Cookie に識別子が入る。
**`X-User-Id` を付けるのは Next.js の proxy（`src/proxy.ts`）だけ**で、ブラウザが付けたヘッダは捨てる。
画面のコードは認証を意識せずに API を呼ぶ。
AWS では、proxy が秘密のヘッダ（`X-Origin-Verify`）も付ける。値は環境変数 `ORIGIN_VERIFY_SECRET` から読み、ローカルでは付けない（[Amplify](#amplifyweb)）。

`/` は所属テナントの数で出し分ける（0 件: 招待を受けていない旨 / 1 件: そのテナントへ / 2 件以上: 選択画面）。
所属の一覧はブラウザから取る。サーバーで取ると、proxy 以外でも利用者の識別を付けることになる。

**利用者が替わると、TanStack Query のキャッシュを作り直す**（ルートレイアウトで `Providers` に利用者をキーとして渡す）。
残すと、前の利用者の応答が次の利用者の画面に出る。

Phase 3 では proxy を「セッションから Cognito のトークンを取り出して `Authorization` に付ける」に替え、
`src/lib/auth/stub-users.ts` とログイン画面を削除する。

### インフラ（Terraform）

AWS のリソースは Terraform で作る。コンソールで直接変えない。
state の置き場所と環境の分け方は [ADR-0011](adr/0011-terraform-state-and-environments.md) を参照。

| ディレクトリ | 内容 | state のキー |
| --- | --- | --- |
| `infra/terraform/bootstrap` | tfstate のバケット | `bootstrap/terraform.tfstate` |
| `infra/terraform/account` | アカウントに 1 つだけ置くもの（予算、コスト配分タグ） | `account/terraform.tfstate` |
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

**apply はローカルから行う。** CI は整形と `validate` だけで、AWS には触れない。
CI からの plan / apply は、OIDC のロールを作る課題で検討する。

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

#### Amplify（web）

`modules/web` で作る。ビルドの手順はリポジトリのルートの `amplify.yml` にある（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）。

```
ブラウザ → Amplify（dev.<ドメイン>、ベーシック認証）→ SSR の proxy → ALB（秘密のヘッダ）→ quiz-service
```

- **スタブ認証の間は、画面をベーシック認証で、API を秘密のヘッダで守る。** どちらも Phase 3 で見直す
- SSR の実行時には Amplify の環境変数が渡らない。`amplify.yml` がビルドの中で、サーバー側で読む値（`API_ORIGIN`、`ORIGIN_VERIFY_SECRET`）だけを `.env.production` に書き出す。
  `NEXT_PUBLIC_` を付けないので、ブラウザ向けのコードには入らない
- pnpm は、ビルドの中でだけ `nodeLinker: hoisted` にする。既定の配置では Amplify が `next` を見つけられない
- **push でビルドしない。** 起動は次のコマンドで行う（CI からの起動は DEV-48）。ビルドは約 3 分

```bash
cd infra/terraform/envs/dev
aws amplify start-job --app-id "$(terraform output -raw web_amplify_app_id)" \
  --branch-name develop --job-type RELEASE
```

ベーシック認証の利用者名とパスワードは、Terraform が作る。

```bash
terraform output -raw web_basic_auth_username
terraform output -raw web_basic_auth_password
```

入れ替えるときは `terraform apply -replace=module.web.random_password.basic_auth`。
Amplify はパスワードをハッシュにして保存するので、Terraform はブランチの値を比べない。作り直したときだけ、`terraform_data` が API で書き換える。

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

- **ALB は秘密のヘッダ（`X-Origin-Verify`）を持たない要求を 403 で返す。** スタブ認証の間、`X-User-Id` で誰にでもなりすませるため。
  ヘッダを付けるのは web（Amplify）の proxy だけで、値は Secrets Manager にある（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）
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

デプロイは GitHub Actions で自動にする（DEV-48）。それまでは次の手順で行う。
**マイグレーションを先に流し、アプリはそのあとで入れ替える**（[マイグレーション](#マイグレーション)）。

```bash
cd infra/terraform/envs/dev
REPO=$(terraform output -raw quiz_service_ecr_repository_url)
TAG=$(git rev-parse --short=12 HEAD)

# 1. イメージを作って push する。タグは上書きできない
aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
docker build --platform linux/arm64 -f ../../../../services/quiz-service/Dockerfile -t "$REPO:$TAG" ../../../..
docker push "$REPO:$TAG"

# 2. terraform.tfvars の quiz_service_image_tag を $TAG にし、マイグレーションのタスク定義だけを先に更新する。
#    plan がタスク定義の置き換えだけであることを確かめる
terraform apply -target=module.quiz_service.aws_ecs_task_definition.migrate

# 3. マイグレーションを流す。終了コードが 0 でなければ、ここで止める
TASK=$(aws ecs run-task \
  --cluster "$(terraform output -raw ecs_cluster_name)" \
  --task-definition "$(terraform output -raw migrate_task_definition_arn)" \
  --launch-type FARGATE \
  --network-configuration "$(terraform output -raw migrate_network_configuration)" \
  --query 'tasks[0].taskArn' --output text)
aws ecs wait tasks-stopped --cluster "$(terraform output -raw ecs_cluster_name)" --tasks "$TASK"
aws ecs describe-tasks --cluster "$(terraform output -raw ecs_cluster_name)" --tasks "$TASK" \
  --query 'tasks[0].containers[0].exitCode'

# 4. アプリを入れ替える
terraform apply
```

動作を確かめるときは、秘密のヘッダを付けて呼ぶ。

```bash
SECRET=$(aws secretsmanager get-secret-value \
  --secret-id "$(terraform output -raw origin_header_secret_arn)" --query SecretString --output text)
curl -H "X-Origin-Verify: $SECRET" -H "X-User-Id: 67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab" \
     "$(terraform output -raw quiz_service_url)/api/t/demo/admin/categories"
```

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
  - 前提: PostgreSQL 16.3 以降 / 無活動時間は 5 分〜24 時間で設定可 / 復帰に約 15 秒
  - **アプリが常時接続を張ると一時停止しない**ため、dev では HikariCP を `minimum-idle: 0` + 短い `idle-timeout` にする
  - 同じ理由で dev では RDS Proxy を使わない
- 通知は Lambda（イベント時のみ課金）で実装し、常駐サービスを増やさない
- 開発環境の ECS タスクは夜間停止（EventBridge Scheduler で desiredCount=0）
- **予算は月 30 ドル。** 実績が 85% と 100% を超えたとき、月末の予測が 100% を超えたときにメールで届く
  （`infra/terraform/account`）。通知先は公開リポジトリに載せないため、`terraform.tfvars`（Git の管理外）で渡す
  - 予測でも知らせるのは、月の途中で止める判断をするため。実績だけだと、気づいたときには超えている
  - クレジットと返金は差し引かずに数える。相殺されて、使った量が見えなくなるのを避ける
- 全リソースに付けている `Project` / `Env` のタグで、Cost Explorer から環境ごとの費用を見る（コスト配分タグ）
