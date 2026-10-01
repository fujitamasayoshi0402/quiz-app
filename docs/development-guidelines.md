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
  いまは `private` のみを扱う（時期は未定。[ロードマップ](ROADMAP.md#時期を決めていないもの)）

ユースケース・画面一覧・URL 構成は [要件定義](requirements.md)、各概念の持ち方は [ドメインモデル](domain-model.md) を参照。

### 一般ユーザー
- パスキー（WebAuthn）でユーザー登録・ログイン
- 所属するテナントのカテゴリ / 難易度を選択してクイズに挑戦
- 4 択問題に回答、正誤判定と解説（Markdown の文章 + draw.io 図解）の閲覧
- 回答履歴・スコアの確認

### 管理者
- パスキーでの管理者登録・ログイン（一般ユーザーとロール分離）
- 招待による管理者・一般ユーザーの追加
- 自テナントのクイズの CRUD（4 択・正解・解説文・解説図）
- カテゴリ / 難易度の CRUD と、それに紐づくクイズ作成
- 解説図は draw.io（`.drawio` ファイル）で作成し、SVG に書き出して配信

### 共通 / 運用
- クイズの追加・更新時に、テナントの管理者が設定した Slack へ通知（[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)）
- まずは Web アプリ、将来的にスマホアプリ展開

## 3. 技術スタック

| 領域 | 採用技術 | 備考 |
| --- | --- | --- |
| バックエンド | Kotlin 2.3 + Spring Boot 4.1 (Java 21) | Gradle Kotlin DSL。[ADR-0008](adr/0008-use-spring-boot-4.md) |
| DB | Aurora PostgreSQL Serverless v2（min 0 ACU / 自動一時停止） | サービスごとにスキーマ分離。コスト最優先 |
| マイグレーション | Flyway | |
| フロントエンド | Next.js (App Router) + TypeScript + Tailwind CSS + shadcn/ui | TanStack Query / Zod。単体テストは Vitest |
| 認証 | Amazon Cognito（Managed Login、パスワード + パスキー） | ロールはアプリのデータで持つ。後で自前実装に差し替える（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)） |
| コンテナ基盤 | ECS Fargate + API Gateway（HTTP API） | Kubernetes は採用しない。ロードバランサーは置かない（[ADR-0019](adr/0019-expose-api-through-api-gateway-http-api.md)） |
| フロントの配信 | Amplify Hosting | [ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md) |
| 非同期 / 通知 | EventBridge → Lambda → Slack Incoming Webhook（DLQ に SQS） | 常駐リソースを増やさない |
| ファイル | S3 + CloudFront（`.drawio` 原本と SVG） | API が出す署名付き URL で配る（[ADR-0017](adr/0017-deliver-figures-with-cloudfront-signed-urls.md)） |
| IaC | Terraform（tfstate は S3 + ロック） | |
| CI/CD | GitHub Actions（AWS 認証は OIDC、アクセスキー禁止） | |
| 監視 | CloudWatch Logs / Metrics、OpenTelemetry | |
| 将来のモバイル | React Native (Expo) | TypeScript 資産を再利用 |

## 4. アーキテクチャ方針

マイクロサービスで構築するが、**最初から細かく割らない**。サービス境界は DB スキーマ単位で分離する。

- `quiz-service` … クイズ / カテゴリ / 難易度の CRUD、出題
- `answer` … 回答受付、採点、履歴、スコア。quiz-service 内のモジュールのまま、物理的には分けない（[ADR-0023](adr/0023-keep-answer-as-module-in-quiz-service.md)）
- `notification-service` … EventBridge ルールから起動する Lambda。Slack へ通知
- 認証は Cognito（マネージド）に寄せ、自前の auth-service は作らない
- モジュール間（quiz と answer）は、呼ぶ側が持つインターフェースで同期に呼ぶ。イベントは、サービスの外へ出すもの（通知）に限る（ADR-0023）
  - **相手のスキーマの表に触れない。相手の型は、2 つのインターフェース（`QuizCatalog`、`AnsweredQuizzes`）に出るものだけを使う。**
    崩れると `ModuleBoundaryTest` が落ちる。相手のデータや型が要るときは、インターフェースに足す
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
├── libs/
│   └── quiz-events/         # サービスの間で共有するイベントの型（ADR-0022）
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
│   ├── api/                 # OpenAPI
│   └── events/              # イベントの JSON の見本
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

**バックエンド。** 整形は ktlint、設計の匂いは detekt が見る。役割が違うので両方走らせる。

```bash
./gradlew :services:quiz-service:ktlintFormat :libs:quiz-events:ktlintFormat   # 自動整形
./gradlew :services:quiz-service:ktlintCheck :services:quiz-service:detekt :libs:quiz-events:ktlintCheck :libs:quiz-events:detekt
```

Kotlin・ktlint・detekt のプラグインの版は、ルートの `build.gradle.kts` が持つ。各プロジェクトは版を書かずに適用する。
プロジェクトごとに版を書くと、プラグインが別々のクラスローダーで読み込まれ、プロジェクトをまたぐ依存が壊れることがある。

ktlint の規約は `.editorconfig` が持つ。**`ktlint_code_style` は `intellij_idea` にしている。**
既定の `ktlint_official` は改行の入れ方が強く、IDE の整形結果と食い違うため。
エディタと lint が別々の形を要求する状態にしない。

detekt で既定から変えたルールは `config/detekt.yml` にあり、それぞれ理由を書いてある。
detekt 1.23.8 は Kotlin 2.0 でコンパイルされているため、**detekt のクラスパスだけ 2.0 系に固定**している。

**web。** 整形は Prettier、書き方の誤りは ESLint が見る（DEV-78）。

```bash
pnpm --filter web format         # 自動整形
pnpm --filter web format:check   # CI と同じ確認
pnpm --filter web lint
```

- 1 行の長さ（120）とインデントは `.editorconfig` から取る。Kotlin と同じ値で、Prettier の設定（`apps/web/prettier.config.mjs`）には書かない
- Tailwind のクラスは、公式の順に並べ替える（`prettier-plugin-tailwindcss`）。`cn` と `cva` の引数も並べ替える
- ESLint からは、見た目に関わるルールを外している（`eslint-config-prettier`）。両方が別々の形を求めないようにする
- **生成物（`src/lib/api/generated/`）は整形しない**（`.prettierignore`）。書き換えると、CI の「作り直して差が出ないか」の検査とぶつかる
- 整形漏れは、commit の前のフック（`lefthook.yml`）と、CI の `frontend` ジョブで止まる。**フックは直さずに知らせるだけ。**
  直したファイルを stage し直すと、一部だけ stage していたときに、stage していない変更まで commit に入る
- 導入のときに全体を整形したコミットは、`.git-blame-ignore-revs` に載せている。GitHub の blame は自動で飛ばす。手元では 1 回だけ設定する（[初回セットアップ](#初回セットアップ)）

### CI

`.github/workflows/ci.yml` が PR と `develop` / `main` への push で動く。

| ジョブ | 内容 |
| --- | --- |
| `changes` | 変更パスを見て後続を出し分ける |
| `backend` | quiz-service。ktlint / detekt → test（Testcontainers）→ カバレッジの集計 → bootJar → イメージのビルド → 脆弱性の検査（Trivy） |
| `notification` | notification-service（Lambda）。ktlint / detekt → test（Testcontainers の LocalStack）→ zip のビルド → 脆弱性の検査（Trivy） |
| `frontend` | API クライアントの作り直しに差が出ないか → 整形 → 型チェック → lint → 単体テスト → build → イメージのビルド → 脆弱性の検査（Trivy） |
| `terraform` | `terraform fmt -check` → 各ルートモジュールの `validate`。AWS には触れない |
| `e2e` | docker compose の定義からイメージを作ってアプリ一式を起動し、Playwright で権限まわりの流れを画面から確かめる（[E2E テスト](#e2e-テスト)） |
| `secrets` | gitleaks で履歴から secret を探す。パスで出し分けず、常に走る |
| `ci` | 先行ジョブの結果を集約する |
| `dependency-graph` | develop への push で、Gradle と pnpm の依存の一覧を GitHub に送る。Dependabot alerts が読む（[脆弱性の検出](#脆弱性の検出)） |
| `deploy-dev` | develop への push で、`ci` が通ったあとに dev へ載せる。何を載せるかは、dev で動いているものと比べて決める（`deploy-dev.yml`。[デプロイ](#デプロイ)） |

**Ruleset の必須チェックには `ci` だけを指定する。** ジョブを足すたびに設定を触らずに済み、
パスの出し分けでスキップされたジョブが「報告されないまま待ち続ける」状態にもならない。

ツールのバージョンは CI でも `.mise.toml` から取る。CI 側で別に指定すると二重管理になる。

**アクションは、タグではなくコミットの SHA で固定する**（`uses: actions/checkout@<SHA> # v7.0.1`）。
タグは付け替えられるため、アクションのリポジトリが乗っ取られると、書き換えられたコードがそのまま動く。
デプロイのジョブは AWS のロールを引き受けるので、dev にまで届く。

- 上げるのは Dependabot（`.github/dependabot.yml`）。週に 1 回、すべてのアクションをまとめて 1 本の PR にする。行末のコメントの版も一緒に書き換わる
- 公開から 7 日たっていない版は入れない。乗っ取りで出された版は、公開から数日のうちに見つかることが多い
- 手で足すときも SHA で書く。SHA はリリースのタグから引く（`gh api repos/<所有者>/<名前>/commits/<タグ> -q .sha`）
- `gradle/actions` は v6 から、キャッシュの部分が MIT ではなく Gradle の利用規約で配られる。公開リポジトリは無料のため、同意して使っている

**ランナーの OS も版を固定する**（CI は `ubuntu-26.04`、デプロイは `ubuntu-24.04-arm`）。`ubuntu-latest` は GitHub の予定で切り替わり、その日から CI が落ちうる。
上げるときは、新しい版で全ジョブを流してから替える。古い版がなくなる前には、実行の注意（annotation）で予告が出る。

PR に新しく push すると、動いている古い実行は止まる。develop / main への push では止めない。
develop では最後にデプロイが走り、マイグレーションの途中で止めると、どこまで流れたかが分からなくなる。

ただし、**待っている実行は取り消される。** GitHub は、同じ concurrency のグループで待てる実行を 1 つに限る。
develop に続けてマージすると、間の実行は流れないまま取り消される。デプロイはこれを前提に、直前の push ではなく、
dev で動いているものとの差で載せるものを決めている（[デプロイ](#デプロイ)）。

**コンテナイメージのビルドは、レイヤーを GitHub Actions のキャッシュに持ち越す**（DEV-87。buildx の `type=gha`）。
依存の層を作り直さない。

- **キャッシュに書くのは develop / main への push だけ。** PR は読むだけにする（`ci.yml` の `IMAGE_CACHE_TO`）。
  PR で書いたキャッシュはその PR からしか読めず、マージしたあとは使われないまま上限（リポジトリで 10 GB）を埋める
- 置き場所は `quiz-service` と `web`（CI。amd64）、`quiz-service-arm64`（デプロイ）に分ける。CPU が違えば中身も違う
- デプロイは、buildx で作ったイメージを docker に読み込み、`docker push` で ECR に上げる。
  buildx の push は ECR のマニフェストを読む権限（`ecr:BatchGetImage`）を要し、デプロイのロールには与えていない
- `e2e` は docker compose の定義から `docker buildx bake` でビルドし、`backend` と `frontend` が書いたキャッシュを読む。
  できたイメージを docker に読み込み、`docker compose up --no-build` で起動する
- quiz-service の Dockerfile は、Gradle の本体と依存を、キャッシュのマウントではなくレイヤーに置く（`downloadDependencies` タスク）。
  マウントの中身はキャッシュに残らず、ソースを変えるたびに Gradle の本体から取り直すことになる
- ECR をキャッシュの置き場所にする方法は採らなかった。PR のジョブは AWS のロールを引き受けられない（Environment `dev` は develop からだけ使える）

**効くのは、イメージの中身が変わらないときと、e2e。** ソースを変えたときは、依存の層（約 340 MB）をキャッシュから取り出すのに 24〜35 秒かかり、
依存を取り直す時間とあまり変わらない。導入の前後で測った、イメージを作る段の時間（CI、amd64）。

| イメージ | 以前 | 中身が変わらない | ソースを変えた |
| --- | --- | --- | --- |
| quiz-service（`backend`） | 約 90 秒 | 5 秒未満 | 約 80 秒 |
| web（`frontend`） | 約 40 秒 | 約 5 秒 | 約 40 秒 |
| 両方を作って読み込む（`e2e`） | 130〜145 秒 | 約 50 秒 | 約 93 秒 |

- develop / main への push では、キャッシュを書く分、10〜20 秒延びる
- さらに縮めるなら、`backend` が Docker の外で作った jar をイメージに入れる（setup-gradle のキャッシュは数秒で戻る）。
  ローカルと CI でイメージの作り方が分かれるため、いまは採らない

**使われなくなったキャッシュは、毎日片付ける**（DEV-101。`cache-cleanup.yml`）。
キャッシュには上限（リポジトリで 10 GB）があり、超えると GitHub が最後に使われたのが古いものから消す。
使われないものが上限を埋めると、たまにしか使わないが効いているもの（デプロイの arm64 のレイヤーなど）が先に消されうる。

- 閉じた PR のキャッシュを消す。その PR からしか読めない
- develop / main のキャッシュは、3 日を超えて使われていないものを消す。中身が変わると新しいキーで書き直され、古いものが残る（Gradle の依存は push のたびに約 240 MB）。
  キーの形からは古いかどうかを決められない。mise は、同じ形のキーでジョブごとに別のツールの組を持つ
- イメージのレイヤー（キーが `buildkit-` / `index-` で始まる）は消さない。どのレイヤーをまだ索引が指しているかは、キーから分からない
- PR を閉じたときには消さない。フォークからの PR では、トークンにキャッシュを消す権限がない
- 手で流すときは、`gh workflow run cache-cleanup.yml -f dry-run=true` で消すものだけを確かめられる

変更のパスの一覧（`.github/path-filters.yml`）は、CI の出し分けとデプロイで共有している。

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

### 脆弱性の検出

依存とコンテナイメージの脆弱性を、2 つの道具で見る（DEV-113）。

| 道具 | いつ | 見る範囲 |
| --- | --- | --- |
| [Trivy](https://trivy.dev/)（CI） | PR と push。`backend` / `notification` / `frontend` のジョブ | 作ったイメージ（quiz-service、web）と、Lambda の zip（notification-service）。jar、Node.js の依存、ベースイメージの OS のパッケージ |
| Dependabot（GitHub） | 新しい脆弱性が公表されたとき | develop の依存（Gradle、pnpm）。見つけたら Security タブに知らせ（alerts）、修正版に上げる PR を作る（security updates） |

**CI が止めるのは、修正版が出ている HIGH 以上だけ**（`trivy.yaml`）。修正版のないものは、止めても待つことしかできない。
Dependabot は、CI を通ったあとに公表された脆弱性を拾う。CI は、PR が足した依存と、作り直したイメージを見る。

- **見るのは、動くときに載るものだけ。** イメージと zip を調べるので、テストやビルドの道具は入らない。
  Gradle の依存の一覧を GitHub に送るときも、実行時の依存（`runtimeClasspath` / `productionRuntimeClasspath`）に絞る（`dependency-graph` ジョブ）
- GitHub は、どちらの依存もファイルからは読み切れない。そのため、Dependabot alerts は `dependency-graph` ジョブが送った一覧を見る
  - Gradle: 版を Spring Boot の BOM が決めるので、`build.gradle.kts` に版がない。依存の一覧を Gradle に解かせて送る
  - pnpm: `pnpm-lock.yaml` を読まず、`package.json` に直接書いた依存（30 件ほど）しか見ない。Trivy で lockfile から一覧を作って送る（間接的な依存を含めて 870 件ほど）。
    ビルドの道具も Amplify のビルドで動くため、開発用の依存も含める
- Dependabot は、Gradle と pnpm について版を上げるだけの PR は作らない（`open-pull-requests-limit: 0`）。脆弱性の修正だけが PR になる
- web のイメージから npm を外している。実行には node だけを使い、npm が抱える依存は検査に掛かるだけ
- Trivy のアクションは使わず、mise で版を固定して入れる（`.mise.toml`）。Trivy のアクションは、タグを乗っ取られて書き換えられたことがある
- 手元でも同じ基準で流せる

```bash
docker build -f services/quiz-service/Dockerfile -t quiz-service:local .
trivy image quiz-service:local                 # trivy.yaml を読む。止まる基準も CI と同じ
```

**見つかったとき。**

1. **直せるなら直す。** 依存は修正版に上げる。Spring Boot が版を決める依存（Tomcat、Jackson など）は、`services/quiz-service/build.gradle.kts` の
   `extra["<名前>.version"]` で上書きする。プロパティの名前は Spring Boot の `spring-boot-dependencies` の pom にある。
   Spring Boot を上げて、同じか新しい版になったら上書きを消す
2. ベースイメージの OS のパッケージは、ベースイメージの更新を待つ。CI は毎回、タグの最新を取り直す
3. **直せないものは `.trivyignore.yaml` に足す。** 影響がない理由（`statement`）と、見直す期限（`expired_at`）を必ず書く。
   期限を過ぎると CI がまた止まり、見直すきっかけになる
4. 公表されたばかりの脆弱性で、関係のない PR まで止まることがある。別の PR で直してから、元の PR を流し直す

### テスト

```bash
./gradlew :services:quiz-service:test   # レポート: services/quiz-service/build/reports/jacoco/test/html/index.html
pnpm --filter web test                  # web の単体テスト（Vitest）
```

| 種類 | 対象 | 置き場所の例 |
| --- | --- | --- |
| 単体テスト | ドメインの不変条件、ユースケースの分岐。DB を使わない | `quiz/domain/QuizTest.kt`、`answer/usecase/AttemptUseCaseTest.kt` |
| API テスト | コントローラから DB まで。Testcontainers の PostgreSQL を使う | `quiz/controller/QuizApiTest.kt` |
| 構造のテスト | 規約が守られているか。守られていなければ落ちる | `TenantBoundaryApiTest`、`TenantIsolationTest`、`OpenApiSnapshotTest`、`ModuleBoundaryTest` |
| スモークテスト | デプロイした環境で、主要な導線が通るか。Newman で流す | `tests/api/` |
| web の単体テスト | 画面の部品が守る性質。DOM を使わず、HTML の文字列にして確かめる | `apps/web/src/components/markdown.test.tsx` |
| E2E テスト | 画面をまたいだ流れ（ログイン、招待、ロールによる出し分け）。Playwright で流す | `tests/e2e/` |

**単体テストにするのは、分岐や不変条件を持つものだけ。** リポジトリへ素通しするだけのユースケースには書かない。
SQL が担うこと（絞り込み・並び順・行レベルセキュリティ・連鎖削除）は、フェイクでは確かめられないので API テストで見る。
web も同じで、崩れると困る性質（解説で HTML を通さないなど）を持つ部品にだけ書く。画面をまたぐ流れは E2E テストが見る。

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
管理（カテゴリ・難易度・クイズを作る）→ 出題 → 回答 → 結果 → 解説図（draw.io、画像、PDF）→ 招待 → テナントの境界 → 片付け、の順に 42 本を呼ぶ。
画像と PDF は、ブラウザと同じく S3 へ直接上げる（`tests/api/fixtures/`）。

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

#### E2E テスト

[Playwright](https://playwright.dev/) で、**権限まわりの流れを画面から**確かめる（`tests/e2e/`）。
ログインのあとに元の画面へ戻るか、ロールによって管理画面に入れるか、招待のリンクで招待された人だけが参加できるか。
API の細かい仕様は JUnit、デプロイした環境のつながりはスモークテストが見る。ここは画面をまたいだ流れだけを見る。

```bash
cd infra/terraform/envs/dev
export E2E_USER_PASSWORD="$(terraform output -raw e2e_user_password)"
cd -

docker compose up -d                                  # ローカルの一式に向けて流す
pnpm --filter e2e exec playwright install chromium    # 初回だけ
pnpm test:e2e
```

- **ログインは、利用者ごとに 1 回だけ Managed Login の画面で行い、Cookie を保存して使い回す**（`auth.setup.ts`）。
  本物のログインの流れを毎回通しつつ、Cognito の画面に依存するのを `support/sign-in.ts` の 1 か所に閉じ込める。
  認証を自前の実装に替えたら（DEV-59）、ここを書き換える
- API でトークンを取ってセッションの Cookie を作る方法は採らなかった。速いが、ログインの画面とコールバックを通らず、テストが Cookie の暗号鍵を持つことになる
- 利用者は、管理者・一般ユーザー・未所属・招待される人の 4 人。Cognito の利用者は Terraform（`modules/auth` の `e2e_user_emails`）が作り、パスワードは全員で共通
- **所属とロールは、テストの前に DB に作る**（`fixtures.sql` を `global-setup.ts` が docker compose の postgres に流す）。
  そのため、docker compose で起動した一式（ローカルと CI）にだけ向ける。dev の DB には E2E の所属がなく、dev でログインしてもどこにも入れない
- 招待される人の所属は、テストの前に外す。何度流しても、同じ状態から始まる
- テスト同士が状態を共有する（招待を受け入れると所属が増える）ため、並列にせず、やり直しもしない
- **パスワードを入力するプロジェクト（setup と login）では、トレースもスクリーンショットも残さない。**
  トレースにも、失敗したときの画面の記録（アクセシビリティのツリー）にも入力した値が残り、CI の成果物は公開リポジトリでは誰でも取り出せる。
  CI が保存するのは、保存した Cookie を使うテスト（e2e のプロジェクト）の結果だけ。ログインの操作が失敗したときは、パスワードの欄を空にしてから失敗させる

CI では `e2e` ジョブが、バックエンドか画面か E2E に関わるものが変わったときに流す。フォークからの PR には secret が渡らないため動かさない。
ローカルと同じく dev の Cognito でログインするため、リポジトリ（Environment ではない）に次を置く。PR のジョブは Environment `dev` を使えない（develop からだけ使える）。

| 名前 | 種類 | 値 |
| --- | --- | --- |
| `AUTH_ISSUER` | variable | `terraform output -raw auth_issuer` |
| `AUTH_CLIENT_ID` | variable | `terraform output -raw auth_client_id` |
| `AUTH_CLIENT_SECRET` | secret | `terraform output -raw auth_client_secret` |
| `E2E_USER_PASSWORD` | secret | `terraform output -raw e2e_user_password` |

**Dependabot の PR には、リポジトリの secret が渡らない**（変数は渡る）。上の 2 つの secret は、Dependabot の secret にも同じ値を置く。
無いと、Dependabot の PR で `e2e` が落ち、マージできない。

```bash
cd infra/terraform/envs/dev
terraform output -raw auth_client_secret | gh secret set AUTH_CLIENT_SECRET --app dependabot
terraform output -raw e2e_user_password | gh secret set E2E_USER_PASSWORD --app dependabot
```

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

### イベント

quiz-service は、クイズの変更をイベントとして送る（[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)）。
型は `libs/quiz-events` にあり、受け手（notification-service）と共有する。**共有するのは型だけ**で、JSON の読み書きはそれぞれのサービスが持つ。

- **クイズの変更と同じトランザクションで、`quiz.outbox` に書く。** 変更が失敗すれば、イベントも残らない
- 1 回の操作で 1 つ。何も変えずに保存したときは書かない。削除と復活は書かない
- 正解、選択肢、解説は載せない。バスとアーカイブとログは quiz-service の外にある
- quiz-service は、HTTP の応答とは別の JsonMapper でイベントを書く（`QuizEventJson`）。API のための設定の変更が、イベントの形に及ばないようにする

**形は `docs/events/` の見本に固定する。** 2 つのサービスは別々にデプロイされ、送る側と受ける側の版は一時的にずれる。
送る側は書き出したものが見本と同じであることを（`QuizEventSamplesTest`）、受ける側は見本を読めることを確かめる。

```bash
# イベントの形を変えたら、見本を作り直してコミットする
UPDATE_EVENT_SAMPLES=true ./gradlew :services:quiz-service:test --tests '*QuizEventSamplesTest'
```

作り直す前に、受け手が新しい形を読めるかを考える。**項目を足すだけなら版（`version`）を上げない。**
消す・意味を変えるときは版を上げ、受け手が新旧どちらも読めるようにしてから、送る側を替える。

**送るのは 2 か所**（DEV-97。`quiz/infrastructure/outbox/`）。

| いつ | 何が | 送れなかったら |
| --- | --- | --- |
| コミットの直後 | `OutboxPublisher`。別のスレッドで送り、送れたら `published_at` を付ける。**操作の応答は、送れたかどうかを待たない** | 行が残る |
| 利用者の要求で DB を使ってから 5 分の間、1 分ごと | `OutboxRelay`。1 分より古い送れていない行を、テナントをまたいで古い順に送る | 次の回、または次に誰かが使ったとき |

- **拾い直しは、いつも動かさない。** 定期的に DB へ接続すると、Aurora が一時停止しなくなる（min 0 ACU）。
  利用者を特定できた要求（毎回 DB から利用者を引く）があってから、5 分だけ動く。拾い直し自身が DB を使っても数えない
- **起動したときには拾わない。** 夜間の停止の明け（8:00）に、毎朝 Aurora を起こさない
- そのため、送れなかったものは**次に誰かが使うまで遅れる。** 通知は急がないため、受け入れている（ADR-0022）
- 拾い直しは `app.outbox_relay` を立て、`FOR UPDATE SKIP LOCKED` で読む。デプロイの入れ替え中にタスクが 2 つあっても、同じ行を送らない。それでも重なったものは、受け手がイベントの ID で捨てる
- 拾い直しは、送ってから 7 日たった行を消す。送れていない最も古い行の経過時間をログに出し、3 分を超えるとアラームが鳴る（[アラーム](#アラーム)）
- `PutEvents` は 1 回 10 件まで。一部だけ失敗した応答も扱い、失敗したものは行を残す
- 間隔と時間は `app.events.relay.*`（`EventsProperties`）で変えられる。テストでは止め、手で呼ぶ
- ローカルは LocalStack の EventBridge のバス（`quiz-app-local`）へ送る。起動のたびに作る。テストはメモリのバスに送り、EventBridge へ届くことは LocalStack を使うテスト（`EventBridgeEventBusLocalStackTest`）が見る

### コード
- バックエンド: レイヤード（controller / usecase / domain / infrastructure）、テストは JUnit5 + Testcontainers
- フロント: Server Components 優先、API 呼び出しは生成した TanStack Query のフック、応答は Zod で検証
- 管理者が書いた文章（解説）は `components/markdown.tsx` で表示する。**`dangerouslySetInnerHTML` は使わない。**
  生の HTML を通さず、画像は解説図を指したもの（`figure:<図の ID>`）だけを出す
  （[ADR-0018](adr/0018-write-explanations-in-markdown-and-render-on-screen.md)、[ADR-0020](adr/0020-reference-figures-from-explanations-and-add-images-and-pdfs.md)）
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
mise exec -- lefthook install                    # commit の前に、secret と web の整形を確かめるフックを入れる
git config blame.ignoreRevsFile .git-blame-ignore-revs   # 全体を整形したコミットを、blame で飛ばす
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
| quiz-service | `services/quiz-service/Dockerfile` | 依存・ローダー・アプリを層に分けて置く。コードだけの変更で依存の層を送り直さない。ビルドの段でも、Gradle の依存を先に取ってレイヤーに置く（[CI](#ci)） |
| web | `apps/web/Dockerfile` | Next.js の standalone 出力。`node_modules` を丸ごと持たない |

どちらもビルドコンテキストはリポジトリのルートで、root 以外の利用者で動く。
quiz-service は Phase 2 の ECS でも同じイメージを使い、環境の違いは環境変数で渡す（[ECS](#ecsquiz-service)）。
AWS では arm64（Graviton）で動かす。Mac（Apple シリコン）でビルドしたイメージがそのまま使える。
web のイメージはローカル用。AWS では Amplify Hosting がソースからビルドする。

ヘルスチェックは `GET /actuator/health`。**DB には問い合わせない。**
ECS のコンテナのヘルスチェックが定期的に叩くと、Aurora Serverless v2 の自動一時停止（min 0 ACU）が発動しなくなるため。

**`dev,migrate` で流すと、デモ用のシードが入る。** `dev` を付けないとカテゴリもクイズも空のまま立ち上がる。
シードは Flyway の repeatable マイグレーション（`db/seed/`）で、`dev` のときだけ locations に加わる。

`docker compose up` で起動するもの。

| サービス | ポート | 備考 |
| --- | --- | --- |
| web | 3000 | Next.js |
| quiz-service | 8080 | `dev` プロファイル。migrate が成功してから起動する |
| migrate | なし | quiz-service と同じイメージを `dev,migrate` で起動する。マイグレーションとシードを流して終了する |
| PostgreSQL | 5432 | ユーザー / パスワード / DB 名はすべて `quiz` |
| LocalStack | 4566 | S3 / EventBridge / SQS / Secrets Manager / Lambda / SSM / DynamoDB。起動のたびに解説図のバケットと、イベントのバスを作る（中身は再起動で消える）。notification-service は手で載せる（[通知](#通知notification-service)） |

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

AWS では、デプロイでサービスを入れ替える前に、ECS の単発タスクとして流す。
**DB に流れるもの（マイグレーション、シード、Flyway の設定）が、dev で動いているものから変わっていなければ飛ばす**（[デプロイ](#デプロイ)）。
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

**番号は、develop にあるどれよりも大きくする。** 並行するブランチで番号がぶつかったら、後からマージする側が付け直す。
develop の最大より小さい番号を後から足すと、dev ではすでに先の番号まで流れているため、Flyway が検証で止まる（順番を飛ばしたマイグレーションは流さない）。
CI は空の DB から流すので、この失敗は dev へのデプロイまで分からない。

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
| カテゴリ | AWS / インフラ、認証認可、イベント駆動設計、Web アプリの技術スタック | 世界の首都 |
| 難易度 | カテゴリごとに体系が異なる。AWS は同じレベルに SAA / DVA が並ぶ | 1 つ |
| クイズ | 公開 39 問、下書き 1 問。解説は Markdown（箇条書き・表・コード） | 公開 3 問 |

2 つ目のテナントは、テナントの選択と、テナントごとにロールが違うことを見せるためにある。
利用者は所属の数が 0 / 1 / 2 の 3 人で、`/` の出し分けをすべて試せる（[最初の管理者](#最初の管理者)の表）。
**シードの利用者のままではログインできない。** 使うときは、自分のメールアドレスを割り当てる（[最初の管理者](#最初の管理者)）。

ほかに、スモークテスト専用のテナント `smoke` と、その管理者が入る（`R__smoke_data.sql`）。
管理者はメールアドレス（`smoke@example.com`）だけで登録してあり、同じアドレスの Cognito の利用者（Terraform が作る）が最初にログインしたときに結び付く。
カテゴリやクイズはテストが作って消すため、シードでは入れない。

**見に来た人が試すための、共有のデモのアカウント**（`demo@example.com`）も入る（DEV-104）。デモのテナントの一般ユーザーで、クイズは変えられない。
スモークテストの管理者と同じく、メールアドレスだけで登録してあり、同じアドレスの Cognito の利用者（Terraform が作る）が最初にログインしたときに結び付く。
共有のアカウントなので、回答の履歴とランキングへの参加は、見た人同士で共有される。

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
| デモのアカウント | `0b66aaf5-727d-5da9-bf87-6756dff5b046` | `demo`（一般ユーザー）。Cognito の `demo@example.com` が最初にログインしたときに結び付く |

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
- ログインが要る画面（`/t/...`、`/invitations/...`）を未ログインで開くと、proxy が**開こうとしたパスとクエリ**を戻り先にしてログインへ移す。
  レイアウトは開いているパスを知らないため、そこで戻り先を決めると、テナントのトップにしか戻せない

`/` は所属テナントの数で出し分ける（0 件: 招待を受けていない旨 / 1 件: そのテナントへ / 2 件以上: 選択画面）。
所属の一覧はブラウザから取る。サーバーで取ると、proxy 以外でもトークンを付けることになる。

**管理画面は、入口で管理者かどうかを見る**（`components/admin/admin-guard.tsx`）。管理者でなければ中身を描かず、案内を 1 つ出す。
所属していないテナントは、存在しないテナントと同じ表示にする。
**これは表示の都合で、権限の判定ではない。** 判定はバックエンドがパスで行い、画面を通さずに API を呼んでも 403 になる。
入口で止めないと、一般ユーザーが URL を直接開いたとき、画面の中の API がそれぞれ 403 を返してエラーが並ぶ。

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

| 環境 | web | API | 解説図 |
| --- | --- | --- | --- |
| dev | `dev.<ドメイン>`（Amplify） | `api.dev.<ドメイン>` | `figures.dev.<ドメイン>`（CloudFront） |

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
- 見に来た人が試すための、共有のデモのアカウント（`demo@example.com`）も同じ。**パスワードは公開する前提**で、`terraform output -raw demo_user_password`。
  `example.com` は誰も受け取れないので、パスワードを忘れた人の手続き（確認コード）で乗っ取られることはない
- E2E テストの利用者（`e2e-*@example.com` の 4 人）も同じ。パスワードは全員で共通で、`terraform output -raw e2e_user_password`（[E2E テスト](#e2e-テスト)）

Managed Login の画面は、web をつながなくても開ける。ログインのあとは `redirect_uri` に戻る（開いていなければエラーの画面になるが、ログインはできている）。

```bash
cd infra/terraform/envs/dev
echo "$(terraform output -raw auth_managed_login_url)/login?client_id=$(terraform output -raw auth_client_id)&response_type=code&scope=openid+email&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fauth%2Fcallback"
```

#### Amplify（web）

`modules/web` で作る。ビルドの手順はリポジトリのルートの `amplify.yml` にある（[ADR-0012](adr/0012-serve-frontend-on-amplify-hosting.md)）。

```
ブラウザ → Amplify（dev.<ドメイン>）→ SSR の proxy（アクセストークンを付ける）→ API Gateway → quiz-service
```

- **画面にベーシック認証はかけない。** データはログインとテナントの所属で守られる（[ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)）。
  スタブ認証の間は、誰にでもなりすませるためかけていた
- SSR の実行時には Amplify の環境変数が渡らない。`amplify.yml` がビルドの中で、サーバー側で読む値（`API_ORIGIN`、`AUTH_*`）だけを `.env.production` に書き出す。
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

#### ECS（quiz-service）

`modules/quiz-service` で作る。入口は API Gateway の HTTP API で、ロードバランサーは置かない（[ADR-0019](adr/0019-expose-api-through-api-gateway-http-api.md)）。

```
インターネット → API Gateway（HTTPS、api.dev.<ドメイン>）→ VPC リンク → quiz-service（Fargate / arm64 / 0.5 vCPU・1 GB）→ Aurora（IAM 認証）
```

- API Gateway は、ECS が Cloud Map に登録したタスクを引き、VPC リンクから直接送る。ポートまで引けるように SRV レコードで登録している
- **入口では認証しない。** アクセストークンはアプリが検証する（[認証](#認証)）。トークンを持たない要求もタスクまで届き、アプリが DB に触れずに 401 を返す。
  以前は ALB が秘密のヘッダ（`X-Origin-Verify`）で web の proxy からの要求だけを通していたが、やめた。proxy はセッションの無い要求の `Authorization` をそのまま中継するため、ヘッダで守れるものがなかった
- 通すのは `/api/{proxy+}` だけ。アクチュエータなどは API Gateway が 404 を返す。既定のエンドポイント（`*.execute-api.amazonaws.com`）は閉じている
- **送り先の待ち時間は最大 30 秒**（HTTP API の上限）。超えると 504 が返り、画面が再試行する。止まっている Aurora の復帰は、たいてい 20 秒ほどで済む
- スロットリングは 10 件/秒、バースト 30。超えると 429 が返る。叩かれ続けたときの費用に上限を付けている
- TLS は 1.2 / 1.3（HTTP API で選べるのは `TLS_1_2` のポリシーだけ）。証明書は ACM が DNS 検証で自動更新する
- アクセスログは CloudWatch Logs の `/aws/apigateway/quiz-app-dev`。**パスは残さない。** 招待を受け入れる API のパスにはトークンが入る
- **タスクの状態は、コンテナのヘルスチェック（中から `curl` でアクチュエータを叩く）で決まる。** 通るまで Cloud Map で UNHEALTHY のままで、API Gateway は要求を送らない。
  デプロイの入れ替えとサーキットブレーカーも、この結果で判断する
- アクセストークンの発行者と web のクライアントは、タスク定義の環境変数（`AUTH_ISSUER`、`AUTH_CLIENT_ID`）で渡す。マイグレーションのタスクにも渡す。無いと起動しない
- アプリは起動時に DB へつながない（`spring.data.jdbc.dialect`）。マイグレーションのタスクは `quiz_app` に接続できず、
  アプリも起動のたびに一時停止中の Aurora を起こさずに済む
- タスクはパブリックサブネットに置き、パブリック IP から ECR や CloudWatch Logs へ出る（[ADR-0013](adr/0013-run-ecs-tasks-in-public-subnets.md)）。
  受信は API Gateway の VPC リンクからだけ
- DB へは AWS Advanced JDBC Wrapper の `iam` プラグインで接続する。接続先の URL が `jdbc:aws-wrapper:postgresql:` のときだけ使われ、
  ローカルは素の PostgreSQL ドライバのまま。違いはタスク定義の環境変数だけにある
- ログは CloudWatch Logs の `/ecs/quiz-app-dev/quiz-service`（`app/` と `migrate/`）。JSON で出し、14 日で消える（[ログ](#ログquiz-service)）
- 起動に失敗したら、前のタスク定義に自動で戻る（デプロイサーキットブレーカー）
- **深夜（2:00〜8:00、日本時間）は止める。** EventBridge Scheduler がタスクの数を 0 にし、朝に 1 に戻す（`schedule.tf`）。
  止まっている間、API は API Gateway が 503 を返し（送り先のタスクがない）、画面には「サーバーが止まっているか、起動の途中です」と出る。
  タスクの数は Terraform では無視している。夜に apply しても起動しない

止まっている間に使うときは、手で起動する。次の停止の時刻（2:00）にまた止まる。

```bash
aws ecs update-service --cluster quiz-app-dev --service quiz-service --desired-count 1
```

**デプロイは GitHub Actions が行う**（[デプロイ](#デプロイ)）。Terraform が持つのはタスク定義の形（環境変数、ロール、CPU など）までで、
どのイメージを動かすかは持たない。サービスが参照するリビジョンの変化は、Terraform では無視している。

動作を確かめるときは、アクセストークンを付けて呼ぶ。トークンはスモークテストの利用者で取る（[スモークテスト](#スモークテスト)）。

```bash
curl -H "Authorization: Bearer $TOKEN" "$(terraform output -raw quiz_service_url)/api/t/smoke/admin/categories"
```

#### ログ（quiz-service）

AWS では、ログを JSON（ECS 形式）で出す（タスク定義の環境変数 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`）。
Logs Insights が項目を読み取り、要求の ID やテナントで絞り込める。例外のスタックトレースも 1 件のログに収まる（平文では行ごとに分かれる）。
**ローカルは平文のまま。** 手元で JSON を見たいときは、同じ環境変数を付けて起動する。

各行に、要求の文脈が載る（`logging/LogContext.kt`）。

| 項目 | 中身 |
| --- | --- |
| `http.request.id` | 要求の ID。API Gateway の要求の ID を引き継ぐ（アクセスログの `requestId` と同じ値）。応答の `X-Request-Id` にも返る |
| `tenant.id` | パスのテナント |
| `user.id` | アプリの利用者の ID（`core.users.id`） |

**載せるのは ID だけ。** メールアドレス、トークン、Webhook の URL は、文脈にもメッセージにも出さない（`RequestLogApiTest`、`SlackWebhookApiTest`）。

`/api` の要求は、終わりに 1 行を出す（`RequestLogFilter`）。メソッド、ルートの型（`http.route`）、ステータス（`http.response.status_code`）、
かかった時間（`http.duration_ms`）を持つ。**パスではなくルートの型を出す。** 招待を受け入れる API のパスにはトークンが入る。
ヘルスチェックは出さない。

Outbox を送るスレッドにも、要求の ID とテナントを引き継ぐ。拾い直し（`OutboxRelay`）は要求と関係なく動くため、載らない。

Logs Insights では、ロググループ `/ecs/quiz-app-dev/quiz-service` を選んで流す。

```
# 1 つの要求を追う（画面に出たエラーの X-Request-Id、アクセスログの requestId から）
fields @timestamp, log.level, message, error.type
| filter http.request.id = "<要求の ID>"
| sort @timestamp asc

# 5xx の多いテナント
filter http.response.status_code >= 500
| stats count(*) as errors by tenant.id
| sort errors desc

# 遅い API。ルートの型ごとに数える
filter ispresent(http.duration_ms)
| stats count(*) as requests, avg(http.duration_ms) as avg_ms, pct(http.duration_ms, 95) as p95_ms, max(http.duration_ms) as max_ms
  by http.request.method, http.route
| sort p95_ms desc

# 警告と例外
fields @timestamp, log.level, message, error.type, tenant.id, http.request.id
| filter log.level in ["WARN", "ERROR"]
| sort @timestamp desc
```

#### アラーム

異常は、メールで知らせる（DEV-108）。送り先は環境に 1 つの SNS のトピック（`modules/alarms`）で、アドレスは `terraform.tfvars` の `alarm_email`。
**購読は、届いた確認のメールのリンクを開くまで有効にならない。** トピックを作り直したときも、確かめ直す。

| アラーム | 鳴る条件 | 鳴ったら見るもの |
| --- | --- | --- |
| `quiz-app-dev-quiz-service-server-errors` | アプリが 5xx を返した（5 分で 1 回でも） | Logs Insights で `http.response.status_code >= 500` の行を探し、`http.request.id` でその要求のログを追う（[ログ](#ログquiz-service)） |
| `quiz-app-dev-quiz-service-gateway-errors` | API Gateway がタスクから応答を得られなかった（502 / 504 が 5 分で 3 回） | アクセスログの `requestId` から、アプリのログの `http.request.id` を引く。タスクが固まっていないか、DB の接続を待っていないか |
| `quiz-app-dev-quiz-service-outbox-stuck` | Outbox に 3 分以上送れていないイベントがある | アプリのログの「イベントを送れませんでした」で理由を見る。送れるようになれば、拾い直しが送る |
| `quiz-app-dev-notification-dlq-not-empty` | 通知が DLQ に入った（[通知](#通知notification-service)） | DLQ の中身と、Lambda のログ |
| タスクの停止（EventBridge のルール） | quiz-service のタスクが落ちた、起動に失敗した、ヘルスチェックに落ちた | メールの理由と、`/ecs/quiz-app-dev/quiz-service` の止まる前のログ |

数えるものの多くは、アプリが JSON で出すログ（[ログ](#ログquiz-service)）から、メトリクスフィルタで作る（`modules/quiz-service` の `alarms.tf`）。
**ログの項目の名前（`http.response.status_code`、`outbox.oldest_unpublished_seconds`）を変えると、アラームが黙って鳴らなくなる。**

**誤報を出さない。** 夜間の停止、デプロイの入れ替え、止まっている Aurora の復帰は、ふつうに起きる。

- **API Gateway の 5xx の率は使わない。** 夜間の停止中は、送り先のタスクがなく 503 が返る。アプリの 5xx と、API Gateway の 502 / 504 を分けて数え、503 は数えない。昼にタスクがなくなったことは、タスクの停止で分かる
- 止まっている Aurora の復帰が 30 秒を超えると、504 が 1 回出て画面が再試行する。5 分で 3 回からにして、これでは鳴らさない
- タスクの停止は、ECS が自分で止めたもの（デプロイの入れ替え、夜間の停止）とマイグレーションの単発タスクを除く。絞り込みは、本物の EventBridge で確かめた（`aws events test-event-pattern`）
- データが無い時間（使われていない、夜間）は、異常とみなさない
- Outbox の閾値は、拾い直しが動く時間（利用者が DB を使ってから 5 分）より短くする。長いと、測れないうちに拾い直しが止まる

アラームにしなかったもの。

- **デプロイのサーキットブレーカーが戻した**: デプロイのジョブが失敗し、GitHub から通知が届く。重ねない
- **Aurora の復帰の失敗、長すぎる復帰**: 利用者には 5xx か 504 として表れ、上のアラームで分かる。RDS のイベントは、一時停止と復帰のたびに出て、失敗だけを選べない

回復したとき（OK に戻ったとき）は知らせない。タスクが落ちては起動し直すことを繰り返すと、そのたびにメールが届く。

鳴ったときの手順の詳細は、Runbook（DEV-116）にまとめる。

メールまで届くかは、アラームの状態を手で変えて確かめられる。次の評価で、実際の値に戻る。

```bash
aws cloudwatch set-alarm-state --alarm-name quiz-app-dev-quiz-service-server-errors \
  --state-value ALARM --state-reason "通知の確認"
```

#### ダッシュボード

運用で見るものを、CloudWatch のダッシュボード `quiz-app-dev` の 1 画面にまとめている（DEV-109。`modules/dashboard`）。
**コンソールで直接変えない。** 変えたら、Terraform を書き換えて apply する。

```bash
cd infra/terraform/envs/dev
terraform output -raw dashboard_url
```

上から、要求が流れる順に並べている。

| 段 | 載せているもの |
| --- | --- |
| アラーム | [アラーム](#アラーム)の状態 |
| 要求（API Gateway） | 要求の数、応答の時間（p50 / p95、タスクの p95）、4xx・5xx・アプリの 5xx・502 / 504 |
| quiz-service（ECS） | 動いているタスクの数、CPU とメモリ、遅い API（ルートの型ごとの p95。ログから） |
| Aurora | ACU、接続の数と CPU、Outbox の送れていない最も古いイベント |
| イベントと通知 | EventBridge に送った数と失敗、通知のルール（当てはまった・Lambda に送った・送れなかった）、Lambda の実行とエラー、DLQ |
| ログ | quiz-service の警告と例外（新しい順） |

ふつうに見える形。異常と取り違えない。

- **夜間（2:00〜8:00）は、タスクの数が 0 になり、ECS の線が途切れる**（[ECS](#ecsquiz-service)）
- **Aurora の ACU は、使われないと 0 になる**（一時停止）。使い始めの要求は、タスクの p95 が数秒〜20 秒ほどに跳ねる。復帰を待っている
- API Gateway の 5xx には、夜間の停止中の 503 が入る。アプリの不具合はアプリの 5xx で見る
- EventBridge に送った数は、バスごとには出ない（アカウントで 1 つ）。いま送るのは quiz-service だけ

載せていないもの。

- トレース（要求が web から quiz-service、イベントまでどう流れたか）は、DEV-110 で入れる
- Amplify（web）の SSR の時間とエラーは、Amplify のコンソールで見る

費用はかからない。ダッシュボードは 3 つ（それぞれメトリクス 50 個）まで無料で、これは 1 つ・約 20 個。
ログのウィジェット（Logs Insights）だけは、開くたびに読んだ量（1 GB あたり 0.0076 ドル）がかかる。dev の量ではほぼ 0。

#### 解説図（S3 + CloudFront）

`modules/figures` で作る（[ADR-0017](adr/0017-deliver-figures-with-cloudfront-signed-urls.md)）。

```
管理者 → web の proxy → quiz-service → S3（原本と SVG）と quiz.figures（行）
利用者 → <img src="/api/t/{slug}/play/figures/{id}/preview"> → web の proxy → quiz-service（所属と行を確かめる）
       → 302（署名付き URL、期限 5〜10 分）→ CloudFront（figures.dev.<ドメイン>）→ OAC → S3
```

画像（PNG・JPEG）と PDF は、ブラウザが S3 へ直接上げる（ADR-0020）。大きな本体を、web の proxy にも quiz-service にも通さない。

```
管理者 → quiz-service（上げる URL を出す）→ ブラウザ → S3 の incoming/（検査の前。誰にも配らない）
       → quiz-service（完了を受けて中身で種類を決める。画像は読み直して img/ へ。
                       PDF は 1 ページ目を画像にして img/ へ置き、本体はそのまま pdf/ へ写す）→ quiz.figures（行）
```

- **誰に見せるかは quiz-service が決める。** CloudFront は署名を確かめるだけで、署名のない要求と期限の切れた要求は 403 で返す
- **SVG はアプリのオリジンから返さない。** SVG はスクリプトを含められる。CloudFront が CSP（`sandbox`）と `nosniff` を付けて返す
- **draw.io の SVG は、端末の配色によらず明るい色で描かれるようにして置く**（`SvgDocuments.lightOnly`）。
  draw.io は `color-scheme: light dark` と `light-dark()` で書き出し、ダークモードの端末では図形が黒くつぶれる。図は白い背景に出すため、ルートの `color-scheme` を `light` にする。
  図は書き換えないため、これより前に置いた図は「描き直す」で直る
- キーにテナントを含める（`svg/{テナントの ID}/{図の ID}.svg`、`drawio/...`、`img/...`、`pdf/...`、`incoming/...`）。
  CloudFront が読めるのは `svg/`、`img/`、`pdf/` の下だけ。原本は API を通して管理者にだけ返し、`incoming/` は誰にも配らない
- 種類は、申告ではなく中身の先頭のバイトで決める。大きさの上限も、決まった種類のものを使う
- **画像は、上がってきたものをそのまま配らない。** 中身の先頭のバイトで PNG か JPEG かを確かめ、画像として読み直して置く
  （`quiz/domain/FigureImage.kt`）。位置情報などのメタデータは残らない。写真の向き（EXIF）は、落とす前に画素へ反映する
- 画像は 10 MB・5,000 万画素まで。長い辺は 2,000 px までに縮める。展開する前にヘッダで画素数を確かめ、読むときも間引いて読む
- **PDF は読み直さず、そのまま置く**（20 MB まで）。バケットの中で `incoming/` から `pdf/` へ写し、ブラウザの PDF ビューアで新しいタブに開く。
  配るときの応答ヘッダは図と同じ（CSP の `sandbox` の下でも、Chrome と Safari の PDF ビューアは開く）
- **PDF の 1 ページ目は、画像にして解説の中に出す**（[ADR-0021](adr/0021-render-first-page-of-pdfs-as-images.md)、`quiz/domain/FigurePdf.kt`）。
  完了を受けたとき、quiz-service が PDFBox で描き、長い辺 2,000 px の JPEG にして、同じ図の ID で `img/` に置く。
  本文の中に出す画像は `/play/figures/{id}/preview` が送る（PDF は 1 ページ目の画像、ほかは図そのもの）。開くのにパスワードが要る PDF は受け付けない
- PDF に埋め込まれていない日本語のフォントは、イメージに入れた IPAex ゴシックで代わりに描く（`services/quiz-service/Dockerfile`）。
  テストは日本語の文字を描かないため、手元にこのフォントがなくても通る
- 上げる URL は署名付き PUT で、種類と大きさを署名に含める（期限 5 分）。AWS SDK for Java v2 は、大きさの範囲を条件にできる署名付き POST を作れないため。
  大きさは、完了を受けたときにも確かめる
- バケットの CORS は、画面のオリジン（`dev.<ドメイン>`）からの PUT だけを許す。`incoming/` に残ったものは 1 日で消える
- アプリのロールは、バケットの一覧（ListBucket）も持つ。一覧の権限がないと、S3 は無いオブジェクトを 404 ではなく 403 で返し、
  「まだ上がっていない」と見分けられない
- **図は変えない。** 描き直した図は新しい ID になる。キャッシュを無効にする操作は要らない
- 署名の秘密鍵は Terraform が作り、SSM Parameter Store（SecureString）に置く。アプリのタスクに ECS の `secrets` で渡す
- 証明書は CloudFront のため us-east-1 に置く（`aws.us_east_1` の provider）
- ローカルには CloudFront がない。LocalStack の S3 の署名付き URL を返す。応答ヘッダと署名の検証は、dev のスモークテストで確かめる

**図は、解説の本文から ID で指す**（[ADR-0020](adr/0020-reference-figures-from-explanations-and-add-images-and-pdfs.md)）。
`![代替テキスト](figure:<図の ID>)` で本文の中に出し、`[文字](figure:<図の ID>)` で新しいタブに開く。
クイズを保存するとき、指している図がテナントにあるかを quiz-service が確かめる。クイズと図を結ぶ表は持たない。
利用者の画面（学習モードの正誤と解説、結果）も、同じ部品（`components/markdown.tsx`）で図を出す。
図は押すと新しいタブで開く（PDF は PDF を開く）。狭い画面では縮んで細部が読めないため、開いた先で拡大して見る。取れない図は、代わりの文字を出す。

管理画面のクイズの編集では、「図を描く」で draw.io（`embed.diagrams.net`）を開き、描いた図を置いて本文に入れる（`components/admin/figure-editor.tsx`）。
「画像・PDF を入れる」は、選んだファイルを上げて本文に入れる（`lib/figure-upload.ts`）。画像は本文の中に出す。
PDF は、1 ページ目の画像と、その下にファイル名を文字にした開くリンクを入れる。描き直せるのは draw.io の図だけ。

- draw.io とは `postMessage` でやり取りする。**受け取るのは、埋め込んだ draw.io の window からのメッセージだけ。** 送り元（origin）と window の両方を確かめる
- 図のデータはブラウザの中で扱われ、draw.io のサーバーには送られない。draw.io の画面そのものは `embed.diagrams.net` から読み込む
- 描き直した図は新しい ID で置き、本文の参照を差し替える。前の図は消さない。保存する前に編集をやめると、保存済みの解説は前の図を指したままのため

鍵を入れ替えるときは、`modules/figures` に新しい鍵の組を足してキーグループに加え、アプリのタスクの鍵を替えてデプロイしてから、古い公開鍵を外す。
発行済みの URL は最長 10 分で切れる。

中身を見るときは、バケットを直接読む。**CloudFront の URL は、API が出したものでないと開けない。**

```bash
cd infra/terraform/envs/dev
aws s3 ls "s3://$(terraform output -raw figures_bucket_name)/svg/" --recursive
```

#### 通知の設定（SSM）

テナントの管理者が、管理画面の「通知」で Slack の Incoming Webhook の URL を設定する（[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)、DEV-102）。

```
管理者 → web の proxy → quiz-service ─┬─ SSM Parameter Store（SecureString）: URL
                                      └─ quiz.slack_webhooks: 設定したという印と日時
```

- **URL は、画面にも API の応答にもログにも出さない。** `GET` は設定したかどうかと日時だけを返す。変えたいときは新しい URL で置き換える
- URL は `/quiz-app/dev/tenants/{テナントの ID}/slack-webhook-url` に置く。**アプリのロールは、この名前への書き込みと削除だけを持ち、読めない。**
  読むのは notification-service だけ（DEV-98）
- 設定したかどうかは、SSM ではなく DB の印で答える。SSM に問い合わせるには読む権限が要り、AWS 管理のキー（aws/ssm）では値まで読めてしまう
- `https://hooks.slack.com/` の下を指し、英数字と `/`・`_`・`-` だけでできた URL だけを受け付ける（`SlackWebhookUrl`）。任意の URL を許すと、Lambda が管理者の指定した先へ要求を送る踏み台になる
- SSM への書き込みは、印を書く DB のトランザクションの中で行う。SSM が失敗すれば印も残らない（503 を返す）
- URL は Terraform を通らないため、state にも残らない
- ローカルは LocalStack の SSM に置く。再起動で消える

置かれているかどうかは、名前の一覧で確かめる。**値は読まない。**

```bash
aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/quiz-app/dev/tenants/" \
  --query 'Parameters[].[Name,LastModifiedDate]' --output table
```

#### イベント（EventBridge）

`modules/events` で作る（[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)、DEV-97）。

```
quiz-service（Outbox。コミットの直後と拾い直し）→ EventBridge のカスタムバス（quiz-app-dev。アーカイブ 7 日）→ ルール → notification-service（DEV-98）
```

- 既定のバスは使わない。アプリのロールが送れるのは、このバスだけ（`events:PutEvents`）
- ECS のタスクはパブリック IP から EventBridge へ出る。VPC Endpoint は要らない（ADR-0013）
- バスの名前は、アプリのタスク定義の環境変数（`EVENTS_BUS_NAME`）で渡す。Terraform で変えたら、デプロイを手で流して反映する（[デプロイ](#デプロイ)）
- **全イベントをログに流すルールは置かない。** イベントには問題文の冒頭とテナントの名前が入る。テナントのクイズの内容を、運用者のログに残さない

届いたかどうかは、アーカイブのイベント数で確かめる。数は少し遅れて増える。

```bash
cd infra/terraform/envs/dev
aws events describe-archive --archive-name "$(terraform output -raw events_archive_name)" \
  --query '[State,EventCount,SizeBytes]'
```

拾い直しが動いたかは、アプリのログ（`/ecs/quiz-app-dev/quiz-service`）の「送れていなかったイベントを拾い直しました」で分かる。

#### 通知（notification-service）

`modules/notification-service` で作る（[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md)、DEV-98）。
コードは `services/notification-service`（Kotlin、Spring は使わない）。

```
カスタムバス → ルール（通知するものだけ）→ Lambda（非同期。VPC の外。Java 21 / arm64 / 512 MB）
  ├ SSM Parameter Store: テナントの Webhook の URL を読む（無ければ何もしない）
  ├ DynamoDB: イベントの ID で重複を捨てる
  └ Slack（Incoming Webhook、Block Kit）
送れなかったもの → SQS（DLQ、14 日）→ CloudWatch のアラーム → SNS → メール
```

- **何を通知するかは、ルールが決める**（`event-pattern.json`）。公開の状態の `QuizCreated` と `QuizUpdated`、`QuizPublished`、公開のものを含む `QuizzesImported`。
  下書きの編集と、公開を下書きに戻したことは知らせない。絞り込みは `EventPatternTest` が LocalStack の EventBridge に同じ JSON を当てて確かめる
- 通知には、何が起きたか、問題文の冒頭、テナント、カテゴリ、管理画面へのリンクを載せる（`SlackMessages`）。
  管理者が書いた文字の `<` `>` `&` は書式として読ませない。`<!channel>` のような全員への呼び出しを、問題文から作れないようにする
- **Webhook の URL はログに出さない。** 通信の例外もつながない。例外の文に URL が入ると、Lambda が失敗として書くログに残る
- 送る前にも、URL が `https://hooks.slack.com/` の下を指すかを確かめる。リダイレクトはたどらない
- 重複は DynamoDB の条件付きの書き込みで捨てる（`DynamoDbDeliveries`）。「処理中」として書いてから送り、送れたら「済み」にする。記録は TTL で 7 日後に消える（アーカイブと揃える）
  - **処理中の期限は 1 分。** Lambda の時間切れ（30 秒）より長く、非同期呼び出しの再試行の間隔（約 1 分）より短くする。
    ADR-0022 の 5 分では、時間切れで落ちたときに 2 回の再試行がどちらも「処理中」として捨てられ、DLQ にも入らずに通知が消える
- Slack が 429・5xx を返したとき、通信に失敗したときは、記録を消して例外で終わる。Lambda が 2 回まで再試行し、尽きたら DLQ に入る。
  ほかの 4xx（Webhook が消された、など）は、ログに残して終わる
- 知らない版のイベントは、例外で終わらせて DLQ に残す。受け手を直してから、アーカイブから流し直す
- 起動の速さとメモリ（DEV-98 で dev で測った。512 MB）: 起動したばかりの環境では、初期化 1.2 秒と処理 5.6 秒（SDK と TLS の初回の準備）。
  続けて呼ばれた環境では 0.4 秒。使ったメモリは 206 MB。通知は急がないため、SnapStart は入れていない
- **DLQ に 1 件でも入ると、メールが届く。** 送り先は、ほかのアラームと共通のトピック（[アラーム](#アラーム)）

**コードは Terraform では載せ替えない。** 関数を作るときにだけ zip を読む（`package_path`）。以後は、develop へのマージでデプロイが載せる（[デプロイ](#デプロイ)、DEV-99）。
quiz-service の ECS と同じく、Terraform が持つのは関数の形（ロール、環境変数、メモリなど）までで、どのコードを動かすかは持たない（ADR-0015 と同じ分け方）。

```bash
./gradlew :services:notification-service:buildZip   # 関数を作る apply の前に要る
```

ログは CloudWatch Logs の `/aws/lambda/quiz-app-dev-notification-service`（14 日）。届いたイベントごとに「知らせました」「通知先が設定されていないため、知らせません」などが 1 行出る。

DLQ に入ったものは、中身を見て原因を直してから、バスのアーカイブから流し直す（重複は捨てられる）。見終えたものは、DLQ から消す。

```bash
cd infra/terraform/envs/dev
aws sqs receive-message --queue-url "$(terraform output -raw notification_dlq_url)" \
  --max-number-of-messages 10 --visibility-timeout 0 --query 'Messages[].Body'
```

**ローカル**では、LocalStack に手で載せる。既定では Slack へ送らず、送る内容を Lambda のログに出す（`SLACK_DELIVERY=log`）。
Webhook は画面の「通知」で、`https://hooks.slack.com/services/...` の形の URL を設定しておく（LocalStack の SSM に入る）。

```bash
docker compose up -d
./gradlew :services:notification-service:buildZip
services/notification-service/scripts/deploy-localstack.sh
aws --endpoint-url http://localhost:4566 logs tail /aws/lambda/quiz-app-local-notification-service --follow
```

- LocalStack は再起動で中身が消える。そのたびにスクリプトを流し直す
- 本当に Slack へ送るときは、`SLACK_DELIVERY=send` を付けてスクリプトを流す
- LocalStack は、Lambda に自分を指す `AWS_ENDPOINT_URL` を渡す。関数のコードは接続先を持たず、SDK がこれを読む

#### デプロイ

develop にマージすると、CI（`ci.yml`）のチェックが通ったあとに、`deploy-dev.yml` が dev に載せる
（[ADR-0015](adr/0015-deploy-by-registering-task-definitions-from-ci.md)）。

```
notification-service（zip を作る → 関数に載せる → 1 度呼んで確かめる）
→ イメージを作る（arm64）→ ECR に push → マイグレーション（単発タスク。変更があるときだけ）→ サービスの入れ替え → web のビルド（Amplify）→ スモークテスト
```

- **dev で動いているものと比べて、変わったほうだけを載せる**（DEV-79）。動いているもののコミットから develop の先頭までに、
  quiz-service は `backend`、web は `frontend` のパス（`.github/path-filters.yml`）が変わっていれば載せる。ドキュメントだけの変更では何もしない
  - 動いているもののコミットは、quiz-service はサービスのタスク定義のイメージのタグ、web は最後に成功した Amplify のビルドから取る（`.github/scripts/deployed-commits.sh`）
  - 直前の push との差で決めると、続けてマージして待ちの実行が取り消されたとき（[CI](#ci)）、その分が載らない。
    動いているものと比べれば、次の実行がまとめて載せる。デプロイが失敗したときも、次の実行が載せ直す
  - 動いているもののコミットが分からないときは、載せる。動いているほうが新しいときは、載せない（古い実行をやり直しても、新しいものを上書きしない）
- **notification-service は、`notification` のパスが変わったときだけ載せる**（DEV-99）。quiz-service だけを変えたときは、関数に触れない
  - **quiz-service より先に載せる。** イベントの受け手は、新しい版を読めるようにしてから送る側を替える（ADR-0022）
  - 載せたコミットは、関数のタグ `DeployedCommit` に残す。Terraform はこのタグを無視する（`envs/dev/versions.tf` の `ignore_tags`）。
    説明（description）や環境変数に書くと、デプロイのロールに関数の形を変える権限（`UpdateFunctionConfiguration`）が要る
  - 載せたあと、知らない種類のイベントで 1 度呼び、起動できるか（環境変数、依存の jar、AWS のクライアント）を確かめる。何もせずに返るので、Slack には送らない
  - **確かめるのに失敗しても、前のコードには戻らない。** その間に届いたイベントは再試行のあと DLQ に入り、メールが届く。
    直したものが載ったら、アーカイブから流し直す。dev だけのため、版とエイリアスで切り替える仕組みは入れていない
  - 失敗したときはタグを書かない。次のデプロイが、直したものを載せ直す
- **マイグレーションは、DB に流れるもの（`migration` のパス）が変わったときだけ流す**（DEV-88）。変わっていなければ、単発タスクを飛ばす（約 1.6 分）。
  飛ばしたかどうかは、デプロイのジョブのサマリーに出る
  - 比べる相手は、quiz-service の動いているもののコミット。**そのコミットまでのマイグレーションは、流し終わっている。**
    サービスは、マイグレーションが成功してからしか替わらないため
  - 動いているもののコミットが分からないときは、流す
  - 手で流すときは `migration=run` で必ず流せる。**飛ばす指定はない。** DB に流れるものを変えたコミットを流さずに載せると、
    それが動いているものになり、以後は差がないとして流れなくなる
- **マイグレーションが失敗したら、サービスは替えない。** 入れ替えで起動に失敗し、前のリビジョンに戻ったときも失敗にする
- タスク定義は、ファミリーの最新のリビジョンからイメージだけを差し替えて登録する。
  **Terraform で形（環境変数など）を変えたら、デプロイを手で流して反映する**（コミットが同じでも載せるよう、`backend=deploy` を指定する）。
  マイグレーションのタスク定義の形を変えたときは、`migration=run` も付ける。付けないと、DB に流れるものが同じなので飛ばす
- 後から始まったデプロイは、先のものが終わるまで待つ。途中で止めない
- **夜間の停止中にデプロイすると、1 つ起動してから載せる。** 止めたままでは、スモークテストで確かめられないため。
  次の停止の時刻にまた止まる
- 失敗すると GitHub から通知が届く。マイグレーションのログは CloudWatch Logs の `migrate/quiz-service/<タスク ID>` にある
- イメージのタグはコミットの SHA（先頭 12 桁）。ECR には直近 10 個が残る
- マージから載り終えるまで約 9 分（イメージ 2 分、quiz-service 5 分、web 2 分）。起動に失敗して前のリビジョンに戻るときは、失敗が分かるまで 15 分ほどかかる

手で流すとき（形を変えたあと、失敗をやり直すときなど）。`backend`、`frontend`、`notification` は `auto`（動いているものと比べる）/ `deploy`（必ず載せる）/ `skip`（載せない）、
`migration` は `auto` / `run`（必ず流す）を指定できる。

```bash
gh workflow run deploy-dev.yml --ref develop                                     # 動いているものと比べて、変わったほうを載せる
gh workflow run deploy-dev.yml --ref develop -f backend=deploy -f frontend=skip  # quiz-service だけを載せ直す（形を変えたあと）
gh workflow run deploy-dev.yml --ref develop -f backend=deploy -f frontend=skip -f migration=run  # マイグレーションも流し直す
gh workflow run deploy-dev.yml --ref develop -f backend=skip -f frontend=skip -f notification=deploy  # notification-service だけを載せ直す
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

# notification-service
(cd ../../../.. && ./gradlew :services:notification-service:buildZip)
../../../../.github/scripts/deploy-notification-service.sh "$(terraform output -raw notification_function_name)" \
  ../../../../services/notification-service/build/distributions/notification-service.zip "$(git rev-parse HEAD)"
```

**GitHub Actions は OIDC でロールを引き受ける。アクセスキーは使わない**（`modules/deploy-role`）。

- 引き受けられるのは、このリポジトリの Environment `dev` で動くジョブだけ。`dev` は develop からしか使えない（GitHub の設定）
- ロールにできるのは、ECR への push、2 つのタスク定義の登録、マイグレーションの起動、サービスの更新、Amplify のビルドの起動、
  notification-service の関数のコードの載せ替え（と、確かめるための呼び出し、`DeployedCommit` のタグ）だけ。ほかの Lambda には触れない
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
**最初のデプロイは `-f backend=deploy -f migration=run` で流す。** このタスクはマイグレーションを経ずに動き始めるため、
「動いているもののコミットまでは流し終わっている」が成り立たず、`auto` では DB が空のままマイグレーションを飛ばしうる。

#### state のバケットを作り直すとき

通常は触らない。バケットごと失った場合だけ、次の順で作る。

1. `bootstrap/backend.tf` を一時的に外し、ローカル state で `terraform init` → `apply`
2. `backend.tf` を戻し、`terraform init -migrate-state` で state をバケットへ移す

## 8. コスト方針

個人で運用する規模のため、常時起動のコストを抑えることを前提に設計する。

- NAT Gateway も VPC Endpoint も使わず、ECS のタスクをパブリックサブネットに置く（[ADR-0013](adr/0013-run-ecs-tasks-in-public-subnets.md)）
  - どちらもタスクを止めても課金が続き、Endpoint は必要な本数 × AZ 数で NAT Gateway より高くなる
  - タスクへの受信は SecurityGroup の参照だけで許し、CIDR では開けない。外からの入口は API Gateway の VPC リンクだけ
- Aurora Serverless v2 は **min 0 ACU**（自動一時停止）を採用。一時停止中はストレージ料金のみ
  - 前提: PostgreSQL 16.3 以降 / 無活動時間は 5 分〜24 時間で設定可（dev は 30 分）/ 復帰に約 15 秒
  - **dev は、最後の接続が切れてから 30 分で一時停止する**（DEV-89）。以前は 5 分で、画面を読んだり解説を書いたりしている間に止まり、
    次の操作のたびに復帰（15〜20 秒）を待っていた。ある 1 日で 8 回以上復帰し、30 分ほどの利用の中で 4 回止まっていた。
    30 分なら、待つのは使い始めの 1 回で済む。起きている時間が 1 回の利用ごとに最大 25 分延び、月 2〜4 ドルほど増える見込み
  - **アプリが常時接続を張ると一時停止しない**ため、dev では HikariCP を `minimum-idle: 0` + 短い `idle-timeout` にする
  - 同じ理由で dev では RDS Proxy を使わない
  - イベントの拾い直しは、利用者が DB を使ってから 5 分だけ動く（[イベント](#イベント)）。一時停止までの 30 分より短いため、止まるまでの時間は延びない
  - **AWS Advanced JDBC Wrapper は `wrapperDialect=pg` にする。** Aurora と判定させると、クラスタの構成を見張る接続を
    プールとは別に張り、最後の利用から 15 分ほど保ち続ける。その間は一時停止しない
  - 実測（DEV-50。当時の設定は 5 分）: 最後の接続が切れてから 5 分で一時停止する。止まっている DB への最初の要求は、復帰（約 13 秒）を待って
    約 19 秒で 200 が返る。2 回目からは通常の速さ
  - マイグレーション（Flyway）も、止まっている DB を起こす。接続を試し直す回数を 6 回にしている（`application-migrate.yml`）。
    既定の 0 回では、復帰の途中で失敗する
  - 復帰を待つのはバックエンド。接続プールの `connection-timeout` を、復帰にかかる時間より長くしている（45 秒）。
    プールを作るときの接続の試み（`initialization-fail-timeout`）は外している。デプロイで替わったタスクが止まっている DB に初めてつなぐと、
    既定では 1 回の失敗ですぐに 500 を返す
    24 時間を超えて止まっていると復帰に 30 秒を超えることがある。API Gateway の待ち時間（30 秒）を超えると 504 が返る。間に合わなかった読み込みは、
    画面の再試行（5xx と通信エラーを 2 回まで、`src/app/providers.tsx`）で拾う。止まったあとの最初の操作は、たいてい読み込み
  - **待っている間、画面は 3 秒を過ぎたら案内を出す**（`components/slow-request-notice.tsx`）。スケルトンのままでは、壊れたのか待てばよいのかが分からない。
    TanStack Query の読み込みと保存をまとめて見るため、画面ごとには書かない。遅い理由は画面からは分からないので、DB を起動しているとは言い切らない
  - 一時停止しないときは、`DatabaseConnections` と RDS のイベント（クラスタ単位の「Initiated pause / resume」）を見る。
    接続の中身は Data API で `pg_stat_activity` を読むと分かる。`rdsadmin` の接続は一時停止を妨げない
- 通知は Lambda（イベント時のみ課金）で実装し、常駐サービスを増やさない
- 開発環境の ECS タスクは深夜（2:00〜8:00）に止める（[ECS](#ecsquiz-service)）。タスクとそのパブリック IP の費用が、月に約 5 ドル減る
  - Lambda は挟まず、EventBridge Scheduler から ECS の UpdateService を直接呼ぶ。タスクの数を変えるだけで、判断することがない
  - 止まっている間にデプロイしたときは、デプロイが起動する。夜に作業しているなら、動いていてほしいため

止めても課金が続くものがある。dev の月額の目安（東京リージョン、1 か月 730 時間）。

| 費用 | 月額 | 深夜の停止 |
| --- | --- | --- |
| API Gateway（HTTP API） | 100 万リクエストあたり約 1.3 ドル。dev の量ではほぼ 0 | — |
| EventBridge（カスタムバス、アーカイブ） | 100 万件あたり 1 ドル。アーカイブは GB あたり 0.1 ドル。dev の量ではほぼ 0 | — |
| Cloud Map（タスクの登録） | 約 0.6 ドル（プライベートのホストゾーン 0.5 ドル、登録したタスク 1 つ 0.1 ドル） | 続く |
| Secrets Manager（Aurora のマスター） | 0.4 ドル | 続く |
| 解説図（S3、CloudFront、SSM のパラメータ、us-east-1 の証明書） | ほぼ 0。CloudFront は月 1 TB と 1,000 万リクエストまで無料枠、SSM の標準のパラメータと ACM は無料 | 続く |
| 通知（Lambda、DynamoDB、SQS） | イベントの数だけで、dev の量ではほぼ 0 | — |
| アラームとダッシュボード（CloudWatch のアラーム 4 つ、メトリクスフィルタのメトリクス 3 つ、ダッシュボード 1 つ、SNS） | ほぼ 0。アラーム 10 個とメトリクス 10 個までは無料枠。超えるとアラーム 1 つ 0.1 ドル、メトリクス 1 つ 0.3 ドル | 続く |
| Route 53 のホストゾーン | 0.5 ドル。このアプリ以外のレコードと共有 | 続く |
| Aurora のストレージ、ECR のイメージ | GB あたり 0.12 ドル / 0.10 ドル。どちらも数 GB 以下 | 続く |
| ECS のタスク（0.5 vCPU / 1 GB） | 約 13.5 ドル（1 時間 0.0246 ドル × 1 日 18 時間） | 止まる |
| タスクのパブリック IPv4 | 約 2.7 ドル | 止まる |
| Aurora の ACU | 使った分だけ（1 ACU 時 0.15 ドル） | 一時停止すれば 0 |
| Amplify | ビルド（1 分 0.01 ドル）と SSR の実行。使った分だけ | — |

**止められない費用は、月に数ドルに収まる。** 以前は ALB がパブリック IPv4 を含めて月に約 25 ドルかかり、使っていなくても減らなかった（[ADR-0019](adr/0019-expose-api-through-api-gateway-http-api.md)）。
- **予算は月 30 ドル。** 実績が 85% と 100% を超えたとき、月末の予測が 100% を超えたときにメールで届く
  （`infra/terraform/account`）。通知先は公開リポジトリに載せないため、`terraform.tfvars`（Git の管理外）で渡す
  - 予測でも知らせるのは、月の途中で止める判断をするため。実績だけだと、気づいたときには超えている
  - クレジットと返金は差し引かずに数える。相殺されて、使った量が見えなくなるのを避ける
- 全リソースに付けている `Project` / `Env` のタグで、Cost Explorer から環境ごとの費用を見る（コスト配分タグ）
