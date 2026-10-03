# セキュリティレビュー（OWASP Top 10:2025）

[OWASP Top 10:2025](https://owasp.org/Top10/2025/) の 10 の観点で、いまの対策と残るリスクを見直した結果（DEV-114）。
コード、Terraform、dev の環境の応答を確かめた。直したものは PR を、残すものは課題か「受け入れる理由」を書く。

**重さ**: 高（ほかの利用者のデータや権限に届く）/ 中（利用者をだます、守りが 1 枚しかない）/ 低（多層の守りの 1 枚、影響が小さい）

## 見つかったもの

| 観点 | 内容 | 重さ | 対応 |
| --- | --- | --- | --- |
| A01 | 管理者用の API かどうかを生の URI で判定しており、Spring がルートを選ぶときの解釈（デコード、`;` 以降を外す）と食い違っていた。テナントの一般ユーザーが、自分のテナントの管理 API に届いた。別のテナントには届かない | 高 | 直した（[#116](https://github.com/fujitamasayoshi0402/quiz-app/pull/116)）。振り分けた先のルートの型で判定する |
| A01 | ログインの戻り先を文字列の先頭だけで確かめており、URL として解くと外部のオリジンになるものを通した（オープンリダイレクト） | 中 | 直した（[#117](https://github.com/fujitamasayoshi0402/quiz-app/pull/117)）。URL として解いてオリジンを比べる |
| A02 | web の応答に、セキュリティのヘッダ（HSTS、CSP、埋め込みの禁止、nosniff）がない。`X-Powered-By` を返している | 中 | 直した（DEV-123）。CSP の本体は Report-Only で、強制に切り替えるのは DEV-127 |
| A01 | 状態を変える要求の守りが、Cookie の `SameSite=Lax` の 1 枚。同じドメインの別のサブドメインからは Cookie が付く | 低 | DEV-124 |
| A06 | 流量の制限が API 全体に 1 つ。1 人が上限まで使うと、全員が 429 になる | 低 | 直した（DEV-125）。quiz-service が利用者ごとに数え、重い操作にはさらに厳しい上限を掛ける |
| A03 | npm の依存に、公開から入れるまでの待ち期間がない（アクションには 7 日ある） | 低 | DEV-126 |

高と中の 2 件は、どちらも「**判定と実際の処理で、同じ値を別の解釈で読む**」形だった。
パスや URL を検査するときは、文字列で見ず、処理する側と同じ部品で解いてから見る。

## 観点ごとの対策と残るリスク

### A01 アクセス制御の不備

| 対策 | 場所 |
| --- | --- |
| テナント配下の全テーブルに行レベルセキュリティ。アプリが絞り込みを書き漏らしても、DB が行を返さない | [ADR-0006](adr/0006-row-level-multi-tenancy.md)、`TenantIsolationTest` |
| 認可はパスで決まる（`/admin` は管理者、ほかは所属）。既定が「所属が必要」で、書き忘れても公開されない | `TenantAccessInterceptor` |
| 全エンドポイントを Spring から列挙し、別のテナントの ID で結果が返らないことを確かめる。ケースのないエンドポイントがあると落ちる | `TenantBoundaryApiTest` |
| 所属していないテナントには 404、所属していて権限がなければ 403。認証の前にテナントの有無を答えない | `AuthorizationApiTest` |
| ロールと所属はトークンではなく DB から引く。所属を外せば次の要求から効く | [ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md) |
| 管理 API と出題 API は URL を分け、出題 API は正解を返さない | 開発ガイドライン「API の URL」 |
| 招待は、ログインした人の確認済みのメールアドレスが一致しなければ受け入れられない。DB にはトークンのハッシュだけを持つ | `Invitation` |
| 解説図は quiz-service が見せてよいかを決め、期限の短い署名付き URL で配る | [ADR-0017](adr/0017-deliver-figures-with-cloudfront-signed-urls.md) |

残るリスク: 上の表の DEV-124。

### A02 設定の不備

| 対策 | 場所 |
| --- | --- |
| API Gateway は `/api/{proxy+}` だけを通す。アクチュエータと Swagger UI は外から 404 | `modules/quiz-service` |
| ECS のタスクへの受信は VPC リンクからだけ。Aurora はプライベートサブネット | [ADR-0013](adr/0013-run-ecs-tasks-in-public-subnets.md)、[ADR-0019](adr/0019-expose-api-through-api-gateway-http-api.md) |
| S3 は公開しない。CloudFront（OAC）からだけ読め、署名のない要求は 403 | `modules/figures` |
| 本番のプロファイルでデモ用のシードを流すと、起動を止める | `SeedDataGuard` |
| AWS の変更は Terraform だけで行い、コンソールで触らない | 開発ガイドライン「インフラ」 |

web はセキュリティのヘッダを付けている（開発ガイドライン「画面のセキュリティのヘッダ」）。

残るリスク: CSP の本体は Report-Only で、スクリプトの差し込みをまだ止めない（DEV-127）。API の応答（JSON）にはセキュリティのヘッダがないが、ブラウザが文書として開くものではない。

### A03 ソフトウェアのサプライチェーンの不備

| 対策 | 場所 |
| --- | --- |
| CI が、作ったイメージと Lambda の zip を Trivy で調べ、修正版のある HIGH 以上で止める | 開発ガイドライン「脆弱性の検出」 |
| Dependabot が、Gradle と pnpm の依存の脆弱性を知らせ、修正の PR を作る | `.github/dependabot.yml` |
| アクションはコミットの SHA で固定し、公開から 7 日たっていない版を入れない | 開発ガイドライン「CI」 |
| 依存は lockfile どおりに入れる（`--frozen-lockfile`）。pnpm は依存の install スクリプトを既定で動かさない | `amplify.yml`、`apps/web/Dockerfile` |
| 道具の版は mise でパッチまで固定する | `.mise.toml` |

残るリスク: 上の表の DEV-126。Gradle には待ち期間の仕組みがない。Spring Boot の BOM が版を揃えるため、直接の依存を勝手に上げない運用で抑える。

### A04 暗号の不備

| 対策 | 場所 |
| --- | --- |
| API Gateway と CloudFront（解説図）は TLS 1.2 以上だけを受ける | `modules/quiz-service`、`modules/figures` |
| DB へはパスワードを持たず、IAM 認証で接続する | [ADR-0014](adr/0014-connect-to-aurora-with-iam-auth.md) |
| セッションの Cookie は、トークンを A256GCM で暗号化し、`HttpOnly` と `Secure` を付ける | `apps/web/src/lib/auth/session.ts` |
| 図の署名の秘密鍵と Slack の Webhook の URL は、SSM の SecureString に置く | `modules/figures`、[ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md) |
| 招待のトークンは `SecureRandom` で作り、SHA-256 のハッシュだけを持つ | `Invitation` |

残るリスク: なし。

### A05 インジェクション

| 対策 | 場所 |
| --- | --- |
| SQL は値をすべてプレースホルダで渡す。文字列で組み立てる部分は、コードの中の固定の断片だけ | `*Jdbc*.kt` |
| 解説の Markdown は生の HTML を通さない。`dangerouslySetInnerHTML` を使わない。画像は解説図を指したものだけを出す | [ADR-0018](adr/0018-write-explanations-in-markdown-and-render-on-screen.md)、`markdown.test.tsx` |
| SVG はアプリのオリジンから返さない。CloudFront が CSP の `sandbox` と `nosniff` を付けて返す | [ADR-0017](adr/0017-deliver-figures-with-cloudfront-signed-urls.md) |
| 画像は読み直してから置く（メタデータも落ちる）。種類は申告ではなく中身で決める | `FigureImage` |
| Slack の通知は、管理者が書いた文字の `<` `>` `&` を書式として読ませない | `SlackMessages` |

残るリスク: なし。

### A06 安全でない設計

| 対策 | 場所 |
| --- | --- |
| テナントの分離を、DB・リポジトリ・認可・テストの多層で守る | 開発ガイドライン「テナントの分離」 |
| Slack の Webhook は `https://hooks.slack.com/` の下だけを許し、送る側でも確かめ直す。リダイレクトはたどらない | [ADR-0022](adr/0022-publish-quiz-events-through-outbox-and-notify-slack-per-tenant.md) |
| アップロードは大きさと画素数に上限を設け、展開する前にヘッダで確かめる | 開発ガイドライン「解説図」 |
| API Gateway のスロットリングで、叩かれ続けたときの費用に上限を付ける | `modules/quiz-service` |
| 利用者ごとの流量の上限。1 人が使い切っても、ほかの利用者は使える。重い操作（画像の読み直し、取り込み、招待）はさらに厳しく | `ratelimit/`、`RateLimitApiTest` |

残るリスク: アクセストークンのない要求（401）は利用者ごとに数えられず、API 全体の上限で受ける。叩き続けられると、その間はほかの利用者も 429 になる。画面からの要求は web の proxy を通るため、IP で分けられない。受け入れる（利用者は数人で、費用には全体の上限が付いている）。

### A07 認証の不備

| 対策 | 場所 |
| --- | --- |
| 認証は Cognito の Managed Login（パスワードとパスキー）。パスワードは 12 文字以上 | [ADR-0016](adr/0016-authenticate-with-cognito-managed-login.md)、`modules/auth` |
| ログインのたびに `state` と PKCE を使う | `apps/web/src/app/auth` |
| 利用者が存在するかを、ログインの応答で答えない（`prevent_user_existence_errors`） | `modules/auth` |
| アクセストークンの署名、発行者、期限、種類（`token_use`）、発行先のクライアントを確かめる | `AccessTokens` |
| ログアウトでリフレッシュトークンを失効させる | `apps/web/src/app/auth/logout` |

残るリスク（受け入れる）:

- ログアウトしても、発行済みのアクセストークンは期限（1 時間）まで有効。トークンはブラウザに渡らず、web のサーバーの Cookie の中にしかないため、受け入れる
- MFA は使わない。パスキーを勧めている。認証を自前の実装に替えるとき（DEV-59）に見直す

### A08 ソフトウェアとデータの完全性の不備

| 対策 | 場所 |
| --- | --- |
| デプロイは OIDC でロールを引き受け、アクセスキーを持たない。ロールは Environment `dev`（develop からだけ）に限る | `modules/deploy-role` |
| デプロイのロールは、決まったリソースの決まった操作しかできない（タスク定義の形や関数の設定は変えられない） | `modules/deploy-role` |
| main と develop への直接の push と force push を禁止し、CI が通らないとマージできない | 開発ガイドライン「Git」 |
| イベントは共有の型で読み、知らない版は捨てずに DLQ に残す | `libs/quiz-events`、`QuizEventReader` |
| 図は書き換えない。描き直すと新しい ID になる | 開発ガイドライン「解説図」 |

残るリスク: なし。

### A09 ログとアラートの不備

| 対策 | 場所 |
| --- | --- |
| quiz-service のログは JSON で、要求ごとの ID、テナント、利用者を載せる | `RequestLogFilter` |
| アクセスログにパスを残さない（招待の受け入れのパスにトークンが入る）。Webhook の URL もログに出さない | `modules/quiz-service`、`Notifier` |
| API の 5xx、タスクの再起動、Outbox の滞留、通知の DLQ でアラームが鳴り、メールが届く | `modules/alarms`、`modules/quiz-service` |
| 拒否したアクセス（所属していない、別のメールアドレスの招待）を WARN で残す | `ApiExceptionHandler` |
| web の SSR のログ（例外、CSP の違反の報告）を CloudWatch Logs に残す | `modules/web` |

残るリスク（受け入れる）: 認可の拒否（403 / 404）が急に増えたことでは鳴らない。いまは利用者が少なく、ログを検索すれば足りる。

### A10 例外の扱いの誤り

| 対策 | 場所 |
| --- | --- |
| 応答は RFC 9457 の形にそろえ、想定外の例外は中身を返さず 500 にする | `ApiExceptionHandler` |
| 権限の判定に要るもの（テナント、ルートの型）が決められないときは、通さない側に倒す | `TenantAccessInterceptor` |
| 読めない Cookie（改ざん、鍵の入れ替え、期限切れ）は、無いものとして扱う | `apps/web/src/lib/auth/session.ts` |
| Slack への送信に失敗したら記録を消して再試行させ、尽きたら DLQ に置く。黙って捨てない | `Notifier` |

残るリスク: なし。

## 次に見直すとき

- 認証を自前の実装に替えるとき（DEV-59、DEV-120〜122）。A07 を見直す
- prod を作るとき（DEV-118、DEV-119）。A02 の設定を、本番の値で見直す
