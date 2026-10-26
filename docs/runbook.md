# Runbook

異常に気づいたときに、何を見て、どう戻すか。対象は dev 環境。prod も同じ手順で、名前の `dev` を `prod` に読み替える。
仕組みの説明は[開発ガイドライン](development-guidelines.md)にあり、ここでは手順だけを書く。

コマンドは、リポジトリのルートから次を済ませた前提で書く。

```bash
aws sso login --profile quiz-app-admin
export AWS_PROFILE=quiz-app-admin
cd infra/terraform/envs/dev
```

## 進め方

1. **範囲を見る。** [ダッシュボード](development-guidelines.md#ダッシュボード)の `quiz-app-dev` で、いつから、どこで（API、タスク、Aurora、イベント）起きているかを見る
2. **影響を見る。** 全員か、一部のテナントか、一部の API か。Logs Insights で `tenant.id` と `http.route` ごとに数える（[ログ](development-guidelines.md#ログquiz-service)）
3. **直近の変更を疑う。** デプロイ（GitHub Actions の `CI` / `deploy-dev`）と `terraform apply` の時刻を、起き始めた時刻と比べる
4. **先に止血する。** 前のリビジョンに戻す（[戻す](#quiz-service-を前のリビジョンに戻す)）、タスクを起動する、など。原因の調査はその後
5. **直す。** develop に PR で入れる。直接 AWS を変えたものは、Terraform に戻す
6. **記録する。** Jira に課題を作り、[障害の記録](#障害の記録)の形で残す

## アラームが鳴った

<!-- この節の見出しは、アラームの説明（メール）からリンクしている（modules/quiz-service/alarms.tf、modules/notification-service/main.tf）。見出しを変えたら Terraform も直す -->

アラームの一覧と条件は、開発ガイドラインの[アラーム](development-guidelines.md#アラーム)にある。

### アプリが 5xx を返した

アラーム `quiz-app-dev-quiz-service-server-errors`。

1. Logs Insights（`/ecs/quiz-app-dev/quiz-service`）で、5xx の要求を探す

   ```
   fields @timestamp, http.request.method, http.route, http.response.status_code, tenant.id, http.request.id
   | filter http.response.status_code >= 500
   | sort @timestamp desc
   ```

2. `http.request.id` で、その要求のログをすべて出す。例外は `error.type` と `error.stack_trace` に入っている

   ```
   fields @timestamp, log.level, message, error.type, error.stack_trace
   | filter http.request.id = "<要求の ID>"
   | sort @timestamp asc
   ```

3. 直前にデプロイしていたら、[前のリビジョンに戻す](#quiz-service-を前のリビジョンに戻す)。そうでなければ、例外から原因を追う

- `SQL の実行に失敗しました` は、マイグレーションとアプリの食い違いのことが多い。マイグレーションのログ（`migrate/quiz-service/<タスク ID>`）も見る
- 扱っていない例外は、要求の終わりの 1 行（ERROR）に例外ごと出る。Tomcat も同じ例外を出すが、そちらには要求の ID がない

### API Gateway が 502 / 504 を返した

アラーム `quiz-app-dev-quiz-service-gateway-errors`。
API Gateway が、タスクから応答を得られなかった。

1. アクセスログ（`/aws/apigateway/quiz-app-dev`）で、`status` が 502 / 504 の `requestId` を探す
2. 同じ値を、アプリのログの `http.request.id` で探す
   - **アプリのログに行がない**: 要求がタスクに届いていないか、まだ終わっていない。タスクが固まっていないか、[タスクの停止](#タスクの停止のメール)が届いていないかを見る
   - **`http.duration_ms` が 30000 を超えている**: アプリは遅れて応答した。DB の接続を待っていた（Aurora の復帰、接続プールの枯渇）ことが多い。ダッシュボードの ACU と接続の数を見る
3. 固まっているなら、タスクを入れ替える。新しいタスクが動いてから、古いタスクが止まる

   ```bash
   aws ecs update-service --cluster quiz-app-dev --service quiz-service --force-new-deployment
   ```

止まっていた Aurora の復帰が 30 秒を超えたときの 504 は、1 回では鳴らない。続くときは、Aurora のイベント（RDS のコンソールの「イベント」）で復帰が止まっていないかを見る。

### イベントを送れていない（Outbox）

アラーム `quiz-app-dev-quiz-service-outbox-stuck`。

1. アプリのログで、「イベントを送れませんでした」を探す。EventBridge の失敗の理由（`errorCode`）が出ている
2. よくある原因
   - **権限がない**（`AccessDeniedException`）: アプリのロールの `events:PutEvents` が、バスに対して許されているか。Terraform の変更が apply されていないことがある
   - **バスがない、名前が違う**: タスク定義の環境変数 `EVENTS_BUS_NAME` と、`terraform output -raw event_bus_name` を比べる
3. 直れば、拾い直しが送る。**拾い直しは、利用者が使ってから 5 分しか動かない。** 直したあとに画面を開くか API を呼んで、送られたことをログの「送れていなかったイベントを拾い直しました」で確かめる

送れていない行は消えない。直すまでの間のイベントも、後から順に送られる。

### データの整合性が崩れている

アラーム `quiz-app-dev-quiz-service-integrity-violations`。
DB の制約で表しきれない決まりが崩れた（[データの整合性](development-guidelines.md#データの整合性)）。1 日に 1 回、利用者が使っている間に確かめている。

1. Logs Insights（`/ecs/quiz-app-dev/quiz-service`）で、崩れたものを出す

   ```
   fields @timestamp, integrity.rule, tenant.id, integrity.subject_id
   | filter ispresent(integrity.rule)
   | sort @timestamp desc
   ```

2. 起き始めた時刻を、直前のデプロイ（シードやマイグレーションを流したか）と、手で流した SQL と比べる
3. 中身を Data API で読む。**マスターで読むため、行レベルセキュリティは効かない。** `tenant.id` と ID で絞って読むだけにする

   ```bash
   aws rds-data execute-statement \
     --resource-arn "$(terraform output -raw database_cluster_arn)" \
     --secret-arn "$(terraform output -raw database_master_user_secret_arn)" \
     --database quiz \
     --sql "SELECT id, status, deleted_at FROM quiz.quizzes WHERE tenant_id = '<tenant.id>' AND id = '<integrity.subject_id>'"
   ```

4. 直す。**画面で直せるなら画面で直す**（ドメインの検証を通る）。公開中のクイズなら、管理画面で下書きに戻してから直す
   - シードが原因なら、シードを直して PR で入れる。`DemoSeedTest` が同じ決まりで確かめる
   - SQL で直すときは、マイグレーションとして PR で入れる。テナント配下の行は、先に `app.tenant_id` を設定する（[マイグレーション](development-guidelines.md#マイグレーション)）
5. 直ったかは、次の日の確認で件数が 0 になったことで分かる。すぐに確かめたいときは、タスクを入れ替えて（前に流した時刻はタスクが持つ）画面を開く

### 通知が DLQ に入った

アラーム `quiz-app-dev-notification-dlq-not-empty`。

1. DLQ の中身を見る。入っているのは Lambda の失敗の記録で、イベントは `requestPayload` の中にある。
   `condition` は諦めた理由（`RetriesExhausted` は 3 回試して失敗した）、`time` は流し直すときの範囲に使う

   ```bash
   aws sqs receive-message --queue-url "$(terraform output -raw notification_dlq_url)" \
     --max-number-of-messages 10 --visibility-timeout 0 --query 'Messages[].Body' --output text \
     | jq -c '{condition: .requestContext.condition, type: .requestPayload["detail-type"],
               eventId: .requestPayload.detail.eventId, version: .requestPayload.detail.version, time: .requestPayload.time}' \
     | sort -u
   ```

   `--visibility-timeout 0` で読むため、同じものが何度か返る（`sort -u` でまとめる）。件数は `aws sqs get-queue-attributes --attribute-names ApproximateNumberOfMessages` で見る

2. Lambda のログ（`/aws/lambda/quiz-app-dev-notification-service`）で、同じイベントの ID の失敗を探す
   - Slack が 429 / 5xx を返し続けた、通信に失敗した: Slack の障害なら、戻るのを待つ
   - 知らない版のイベント（「読めない版のイベントです」）: 受け手（notification-service）を直して載せる
3. 直したら、[アーカイブから流し直す](#通知をアーカイブから流し直す)。範囲は、手順 1 の `time` の最初と最後を含める
4. 見終えたものを DLQ から消す。**戻せない。** 流し直しで届いたことを確かめてから消す

   ```bash
   aws sqs purge-queue --queue-url "$(terraform output -raw notification_dlq_url)"
   ```

### 技術ニュースの下書きを作れなかった

アラーム `quiz-app-dev-news-quiz-drafter-errors`。その日の下書きは作られていない。急がない。やり直しはせず、次の日（7:00）にまた動く。

1. Lambda のログ（`/aws/lambda/quiz-app-dev-news-quiz-drafter`）で、例外を見る
   - `Model use case details have not been submitted`: Anthropic の利用目的の届け出がない。Bedrock のコンソールのモデルカタログから届け出る
   - `ThrottlingException` / `ServiceUnavailableException`: Bedrock の一時的な失敗。次の日を待つ
   - `RSS を読めません`: What's New の RSS が変わったか、止まっている。`curl` で RSS を開いて確かめる
   - `AccessDenied`: 権限（`modules/news-quiz-drafter/iam.tf`）。モデルや推論プロファイルを替えたときに起きる
2. 直したら、急ぐときは手で 1 回動かす（開発ガイドライン「技術ニュースの下書き（news-quiz-drafter）」）。Bedrock の費用がかかる

### タスクの停止のメール

「quiz-service のタスクが止まりました」というメールに、止まった理由（`stoppedReason`）と止まり方（`stopCode`）が入っている。

| 止まり方 | よくある原因 | 見るもの |
| --- | --- | --- |
| `EssentialContainerExited` | アプリが落ちた。メモリ不足（終了コード 137） | 止まる前のアプリのログ（ストリームの名前にタスクの ID が入る）。ダッシュボードのメモリ |
| `TaskFailedToStart` | イメージを取れない、secret（SSM）を読めない | 理由の文。直前の Terraform とデプロイ |
| `Task failed container health checks` | 起動が遅い、固まった | 起動のログ。`/actuator/health` が DB に触れていないか |

ECS は、止まったタスクの代わりを自動で起動する。**同じメールが続くとき**は落ち続けている。直前のデプロイを[戻す](#quiz-service-を前のリビジョンに戻す)。

## 症状から

### 画面に「サーバーが止まっているか、起動の途中です」と出る

API Gateway が 500 か 503 を返している（ProblemDetail ではない `{"message": ...}` の形）。送り先のタスクがない。
アクセスログ（`/aws/apigateway/quiz-app-dev`）では、500 の行の `integrationError` に `No target endpoints found` が入る。

- 使われていない（API への要求が 30 分ない）か、深夜 2:00 を過ぎて止まっている（[ECS](development-guidelines.md#ecsquiz-service)）。画面の「サーバーを起動する」で起こせる（ログインしていれば誰でも押せる）。手で起動するときは下のコマンド。使い終わって 30 分たつと、また止まる
- 「サーバーを起動する」で起きないときは、起こす手順のログ（`/aws/vendedlogs/states/quiz-app-dev-quiz-service-wake`）と、API Gateway のアクセスログの `POST /api/wake` の行を見る

  ```bash
  aws ecs update-service --cluster quiz-app-dev --service quiz-service --desired-count 1
  ```

- 昼なら、タスクが落ちている。[タスクの停止のメール](#タスクの停止のメール)を見る。代わりのタスクは自動で起動し、ヘルスチェックが通るまで数分かかる

### 画面がしばらく読み込み中のまま、または 1 回目だけ失敗する

止まっていた Aurora の復帰を待っている。たいてい 20 秒ほどで済み、画面が再試行する。
ダッシュボードの ACU が 0 から上がっていれば、これ。24 時間を超えて止まっていると、30 秒を超えて 504 になることがある。

### ログインできない

- Managed Login の画面でエラーになる: Cognito の側。コールバックの URL（`dev.<ドメイン>` と `localhost:3000` だけ）から開いているか
- ログインのあと 401 が続く: アプリのログの警告「同じメールアドレスの利用者が、別の認証基盤の ID に結び付いています」を探す。
  Cognito で利用者を作り直したときに起きる。結び直すときは `core.users.external_id` を書き換える（[認証](development-guidelines.md#認証)）
- 全員がログインできない: タスク定義の環境変数 `AUTH_ISSUER` / `AUTH_CLIENT_ID` と、`terraform output` の値を比べる

### 解説図が出ない

- 署名付き URL の期限（5〜10 分）が切れた画面を開き直していないか。再読み込みで新しい URL になる
- 全部の図が 403 になる: CloudFront の署名の鍵と、アプリのタスクの鍵（SSM）が食い違っている。鍵を入れ替えた直後なら、順番を見直す（[解説図](development-guidelines.md#解説図s3--cloudfront)）

### Slack に通知が届かない

順にたどる。どこで止まっているかで、見るものが変わる。

1. アプリのログに「イベントを送れませんでした」がないか（[Outbox](#イベントを送れていないoutbox)）
2. ダッシュボードの「通知のルール」で、当てはまったか。下書きの編集など、通知しないイベントもある（`event-pattern.json`）
3. Lambda のログに「通知先が設定されていないため、知らせません」がないか。管理画面の「通知」で Webhook を設定する
4. DLQ に入っていないか（[DLQ](#通知が-dlq-に入った)）

## デプロイが失敗した

GitHub から通知が届く。`CI` のワークフローの `deploy-dev` のジョブで、どの段で止まったかを見る（[デプロイ](development-guidelines.md#デプロイ)）。

| 止まった段 | 見るもの | 戻し方 |
| --- | --- | --- |
| notification-service を載せる | ジョブのログ。`AccessDenied` なら、デプロイのロールの権限の変更が apply されていない | apply してから、ジョブをやり直す |
| マイグレーション | `migrate/quiz-service/<タスク ID>` のログ | サービスは替わっていない。直したマイグレーションを PR で入れる |
| quiz-service をデプロイする | 起動に失敗し、サーキットブレーカーが前のリビジョンに戻した。[タスクの停止のメール](#タスクの停止のメール)も届く | 前のものが動いている。原因を直して PR で入れる |
| web をビルドする | Amplify のコンソールのビルドのログ | 前のビルドが配られている |
| スモークテスト | ジョブのログで、落ちた要求 | 載ったものが壊れている。[戻す](#quiz-service-を前のリビジョンに戻す) |

**Terraform の変更（権限、環境変数）を含む PR は、マージより前に apply する。** 先にマージすると、デプロイが新しい形を前提に動いて止まる。

やり直すときは、ジョブを再実行するか、デプロイを手で流す。

```bash
gh workflow run deploy.yml --ref develop
```

## 戻す・流し直す

### quiz-service を前のリビジョンに戻す

すぐに戻すための手当て。**直したものは、develop に PR で入れる**（壊した PR を revert する）。
戻したままにすると、次のデプロイが develop の先頭を載せ直す（動いているものが古いため）。

```bash
# 直近のリビジョンと、それぞれのイメージ（タグはコミットの SHA の先頭 12 桁）
for td in $(aws ecs list-task-definitions --family-prefix quiz-app-dev-quiz-service --sort DESC --max-items 5 \
    --query 'taskDefinitionArns[]' --output text | tr '\t' '\n' | grep -v migrate); do
  echo "$td $(aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
done

aws ecs update-service --cluster quiz-app-dev --service quiz-service \
  --task-definition quiz-app-dev-quiz-service:<戻すリビジョン>
```

- **マイグレーションは戻さない。** マイグレーションは 1 つ前のアプリと両立させる規約のため（[マイグレーション](development-guidelines.md#マイグレーション)）、前のアプリはそのまま動く
- 戻ったかは、`aws ecs describe-services --cluster quiz-app-dev --services quiz-service --query 'services[0].deployments'` で見る

### prod のリリースを戻す

すぐに戻すための手当て（DEV-134）。**直したものは、develop で壊した PR を revert し、もう一度リリースする。**
戻したままにすると、次のリリースが main の先頭を載せ直す（動いているものが古いため）。

1. 戻す先を決める。前のリリースのタグ（`gh release list`）のコミット
2. quiz-service は、前のリビジョンに戻す。上の「[quiz-service を前のリビジョンに戻す](#quiz-service-を前のリビジョンに戻す)」の `quiz-app-dev` を `quiz-app-prod` に読み替える
3. web は、前のリリースのコミットで Amplify のビルドを流す

   ```bash
   cd infra/terraform/envs/prod
   aws amplify start-job --app-id "$(terraform output -raw web_amplify_app_id)" --branch-name main \
     --job-type RELEASE --commit-id <前のリリースのコミット（40 桁）>
   ```

4. notification-service は、前のリリースのコミットを手元に取り出し、zip を作って載せる（「[デプロイ](development-guidelines.md#デプロイ)」の手元から流す手順）

- **マイグレーションは戻さない。** 1 つ前のアプリと両立させる規約のため、前のアプリはそのまま動く
- 戻したあと、スモークテストを手元から流して確かめる（開発ガイドラインの「[スモークテスト](development-guidelines.md#スモークテスト)」。`baseUrl` を prod にする）。
  **コレクションも、戻した先のコミットのものを使う**（`git worktree add <場所> <前のリリースのタグ>`）。新しいコレクションは、戻したアプリにない API を呼ぶことがある
- 直したものをリリースせずに最新の main へ戻すときは、`gh workflow run deploy.yml --ref main -f environment=prod`（承認が要る）

DEV-135 で試した時間。web のビルドと quiz-service の入れ替えを同時に始めると、web は約 2 分、quiz-service は約 4 分で終わり、スモークテスト（約 25 秒）まで約 5 分で済んだ。
最新の main へ戻すデプロイは、承認から約 9 分。

### 通知をアーカイブから流し直す

バスのアーカイブ（7 日）から、通知のルールにだけ流し直す。届いたことのあるイベントは、受け手がイベントの ID で捨てる。

```bash
ARCHIVE_ARN=$(aws events describe-archive --archive-name "$(terraform output -raw events_archive_name)" --query ArchiveArn --output text)
BUS_ARN=$(aws events describe-event-bus --name "$(terraform output -raw event_bus_name)" --query Arn --output text)
RULE_ARN=$(aws events describe-rule --name quiz-app-dev-notify-slack --event-bus-name "$(terraform output -raw event_bus_name)" --query Arn --output text)

aws events start-replay --replay-name "redrive-$(date +%Y%m%d%H%M)" \
  --event-source-arn "$ARCHIVE_ARN" \
  --event-start-time <起き始めた時刻（例: 2026-01-01T00:00:00Z）> --event-end-time <直した時刻> \
  --destination "Arn=$BUS_ARN,FilterArns=$RULE_ARN"

aws events describe-replay --replay-name <上の名前> --query '[State,StateReason]'
```

流し直したものがどう扱われたかは、Lambda のログで見る。届いたことのあるものは「処理済みか処理中のイベントのため、知らせません」になり、Slack に二重には届かない。
重複を捨てる記録は 7 日で消えるため、それより前のイベントを流し直すと、もう一度届く。

### Aurora のデータを戻す

消したり壊したりしたデータを、バックアップの時刻に戻す。**元のクラスタは書き換えず、戻した中身で別のクラスタを作り、アプリをそちらにつなぎ替える。**
元のクラスタは、原因を調べ終えるまで残しておける。

戻せる範囲は、自動バックアップの保持（dev は 1 日）の間の任意の時刻（PITR）。

```bash
aws rds describe-db-clusters --db-cluster-identifier quiz-app-dev \
  --query 'DBClusters[0].[EarliestRestorableTime,LatestRestorableTime]' --output text
```

- 使っている間、戻せる最も新しい時刻は 5 分ほど前まで進む。**一時停止している間は、止まった時刻のまま。** 止まっている間は書き込みがないため、失うものはない
- 自動のスナップショット（1 日に 1 つ）からも戻せるが、PITR はその範囲を含む。保持より前に戻したいものは、手でスナップショットを取っておく

手順は Terraform の `database_restore` で行う（`envs/dev` の `variables.tf`）。**最新の develop から流す。** 指定を付けずに apply すると、戻したクラスタを消す。

1. 戻したクラスタ（`quiz-app-dev-restore`）を作る。時刻を書かなければ、戻せる最も新しい時刻になる

   ```bash
   terraform apply -var 'database_restore={restore_to_time="2026-01-01T00:00:00Z"}'   # 時刻は UTC
   ```

2. 中身を確かめる。Data API は、有効にしてしばらくは「有効になっていない」（`HttpEndpointNotEnabledException`）を返すことがある。少しおいてやり直す

   ```bash
   aws rds-data execute-statement \
     --resource-arn "$(aws rds describe-db-clusters --db-cluster-identifier quiz-app-dev-restore --query 'DBClusters[0].DBClusterArn' --output text)" \
     --secret-arn "$(aws rds describe-db-clusters --db-cluster-identifier quiz-app-dev-restore --query 'DBClusters[0].MasterUserSecret.SecretArn' --output text)" \
     --database quiz --sql "SELECT count(*) FROM quiz.quizzes WHERE deleted_at IS NULL"
   ```

3. アプリをつなぎ替える。接続先と IAM 認証の許可が替わる（戻したクラスタは内部の ID が変わり、元の許可ではつなげない）。
   **形が変わるので、apply のあとにデプロイを流す**

   ```bash
   terraform apply -var 'database_restore={restore_to_time="<1 と同じ>",use_for_app=true}'
   gh workflow run deploy.yml --ref develop -f backend=deploy -f frontend=skip
   ```

4. スモークテストが通り、画面からデータが見えることを確かめる

戻したクラスタを使い続けるときは、`terraform.tfvars` に `database_restore` を書いて、指定を付けずに apply しても消えないようにする。
元のクラスタに戻すときは、`use_for_app` を外して apply とデプロイをし、そのあと指定を付けずに apply して戻したクラスタを消す。

**戻らないもの**

- **Cognito の利用者。** Cognito はバックアップの外にある。戻した時刻より後にはじめてログインした人は、`core.users` に行がない。
  次のログインで作り直されるが、アプリの ID は新しくなり、その間に増えた所属と回答の履歴は戻らない。招待されていたなら、招待し直す
- **戻した時刻より後のデータ。** つなぎ替えるまでに元のクラスタへ書かれたものも含む。必要なら、元のクラスタから Data API で読んで、画面から入れ直す
- **解説図。** 図は S3 にあり、DB と一緒には戻らない。戻した行が、後から消した図を指していることがある。
  消した図は 7 日の間、前の版が残っている（バケットの版）。戻すときは、前の版をコピーし直す

  ```bash
  aws s3api list-object-versions --bucket "$(terraform output -raw figures_bucket_name)" --prefix "svg/<テナントの ID>/<図の ID>"
  aws s3api copy-object --bucket <バケット> --key <キー> --copy-source "<バケット>/<キー>?versionId=<前の版の ID>"
  ```

- **Outbox の送れていないイベント。** 戻した時刻に送れていなかったものは、戻したクラスタでもう一度送られる。受け手がイベントの ID で重複を捨てる

dev で測った時間（DEV-117。戻せる最も新しい時刻を指定）。

| 段 | 時間 |
| --- | --- |
| 戻したクラスタができる | 約 6 分 |
| 書き込み先のインスタンスができる（使えるようになる） | さらに約 7 分（合わせて約 13 分） |
| つなぎ替えの apply | 約 4 分 |
| デプロイ（サービスの入れ替えとスモークテスト） | 約 5 分 |
| **戻すと決めてから、アプリが戻したデータで動くまで（RTO の目安）** | **約 25 分** |

失うデータ（RPO の目安）は、使っている間は 5 分ほど。一時停止している間は 0。

## テナントの上限を変える

利用者が画面から作ったテナントには、クイズと図の数に上限がある（クイズ 100、図 50。[ADR-0028](adr/0028-let-signed-in-users-create-their-own-tenant.md)）。
運用者が使うテナントで足りなくなったときだけ、`core.tenants` の 1 行を Data API で書き換える（[ADR-0034](adr/0034-keep-quiz-content-as-packs-and-load-through-api.md)）。
**ほかの利用者のテナントの上限は変えない。** 空（`NULL`）にすると上限がなくなる。

- いま: `my-quiz`（dev）をクイズ 300、図 300 にしてある（DEV-186）。ADR-0034 は上限を外すとしたが、上限は残して 300 にした

```bash
cd infra/terraform/envs/dev
ARN=$(terraform output -raw database_cluster_arn); SECRET=$(terraform output -raw database_master_user_secret_arn)
sql() { aws rds-data execute-statement --resource-arn "$ARN" --secret-arn "$SECRET" --database quiz --sql "$1" --query records; }

sql "SELECT slug, quiz_limit, figure_limit FROM core.tenants WHERE slug = '<slug>' AND deleted_at IS NULL"   # 前
sql "UPDATE core.tenants SET quiz_limit = 300, figure_limit = 300 WHERE slug = '<slug>' AND deleted_at IS NULL"
sql "SELECT slug, quiz_limit, figure_limit FROM core.tenants WHERE slug = '<slug>' AND deleted_at IS NULL"   # 後
```

- `core.tenants` には行レベルセキュリティがない。Data API はマスターとして流れるので、`WHERE` を必ず付け、書き換える前と後に同じ行を読む
- 書き換えた行の数（`numberOfRecordsUpdated`）が 1 であることを確かめる
- アプリは要求のたびに上限を読む。デプロイは要らない

## prod のデモにパックを流す

prod のデモのクイズは、シードではなくデモのパック（`content/demo/`）から流す（[ADR-0034](adr/0034-keep-quiz-content-as-packs-and-load-through-api.md)、DEV-191）。
シードは、デモのテナントと、デモのテナントの管理者（運用者）をメールアドレスだけで登録している（`db/demo/R__demo_base.sql`）。
デモのパックを変えたリリースのあとと、prod を作り直したあとに流す。

前提

- シードの入ったリリースが prod に載っている（マイグレーションが `prod,smoke,demo` で流れた）
- 運用者が、シードのアドレスで **prod の** Cognito にサインアップし、prod の web に一度ログインしている（利用者が結び付く）。dev とは User Pool が別
- quiz-service が動いている。公開の前で止めているなら、流す間だけ動かす（[prod](development-guidelines.md#prod)）

```bash
aws sso login --profile quiz-app-admin
export AWS_PROFILE=quiz-app-admin
cd ~/worktrees/my-project/main/infra/terraform/envs/prod    # prod は main から
export ACCESS_TOKEN=$(../../../../tools/content/token.sh <運用者のメールアドレス>)   # パスワードを聞かれる。期限は 1 時間
API=$(terraform output -raw quiz_service_url)
cd ../../../..

node tools/content/load.ts content/demo --api "$API" --tenant demo --update --prune --dry-run   # 作る・直す・消すものを確かめる
node tools/content/load.ts content/demo --api "$API" --tenant demo --update --prune
```

- `token.sh` は、いる場所の Terraform の出力から web のクライアントを読む。`envs/prod` で流すと prod の Cognito のトークンになる
- `--update --prune` で、パックと同じ状態にする。消すのはパックのカテゴリの中のクイズだけ
- 確かめ方: prod の web で「デモとして入る」を押し、6 カテゴリが出て、解説の図が出る

## prod の公開前の点検

prod を公開する前（タスクの数を 1 にする前）に確かめる（DEV-192）。prod を作り直したときも、同じ一覧で確かめる。
コマンドは `envs/prod` で流す。読むだけで、何も変えない。

| 点検 | 確かめ方 | 満たす状態 |
| --- | --- | --- |
| 最新のリリースでスモークテストが通る | GitHub Actions の `deploy-prod` の「スモークテスト」 | 成功 |
| アラームのメールの購読が有効 | `aws sns list-subscriptions --query "Subscriptions[?ends_with(TopicArn, 'quiz-app-prod-alarms')].SubscriptionArn"` | `PendingConfirmation` ではない |
| 予算の通知 | `aws budgets describe-notifications-for-budget --account-id <アカウント> --budget-name <予算の名前>` | 実績 85% と 100%、予測 100% の 3 つ |
| データを戻せる | `aws rds describe-db-clusters --db-cluster-identifier quiz-app-prod --query 'DBClusters[0].[DeletionProtection,BackupRetentionPeriod]'` | 削除保護あり、保持 7 日。最終スナップショットは Terraform（`skip_final_snapshot = false`）。戻し方は [Aurora のデータを戻す](#aurora-のデータを戻す)。実際には戻さない |
| 外から届く範囲（[セキュリティレビュー](security-review.md)の A02） | `curl -s -o /dev/null -w '%{http_code}' <URL>` | API の `/actuator/health`・`/swagger-ui.html`・`/v3/api-docs` が 404、解説図の CDN に署名なしで 403、バケットに直接 403。API Gateway の既定のエンドポイントは閉じている（`DisableExecuteApiEndpoint`） |
| 画面のヘッダ | `curl -sI https://quiz.<ドメイン>/` | HSTS、`nosniff`、`X-Frame-Options`、CSP の `img-src` に `figures.quiz.<ドメイン>`、`connect-src` に prod のバケット。`X-Powered-By` がない |
| 見に来た人の導線 | prod の web で手で操作する | 「デモとして入る」で 6 カテゴリが出て、解説の図が出る。自分のアカウントで「自分のテナントを作る」ができ、クイズを作って解ける。公開テナントの一覧（`/tenants`）が開ける（prod には公開テナントがないため、空の一覧が出る） |

公開する。

```bash
aws ecs update-service --cluster quiz-app-prod --service quiz-service --desired-count 1 --query 'service.desiredCount'
```

- 公開したら、README のデモの URL とデモのアカウントを prod のものにする。デモのアカウントのパスワードは `terraform output -raw demo_user_password`
- 公開してから 1 週間は、ダッシュボードの `quiz-app-prod` で、Aurora の復帰による待ち（タスクの p95）と 5xx を見る（[ADR-0024](adr/0024-run-prod-in-same-account-and-launch-at-release.md) の緩和策）

## Terraform

- **apply は、最新の develop から流す。** 古いブランチや worktree から流すと、先に入った別の変更が消える
- ロックが残った、SSO が途中で切れた: 開発ガイドラインの[インフラ（Terraform）](development-guidelines.md#インフラterraform)

## 費用が予算を超えそう

予算の通知（85%、100%、月末の予測）が届いたとき。

1. Cost Explorer で、タグ `Env` ごと、サービスごとに見る
2. よくある原因
   - **Aurora が一時停止しない**: ダッシュボードの ACU が 0 に戻らない。接続の数を見て、`pg_stat_activity` で誰がつないでいるかを確かめる（[Aurora](development-guidelines.md#aurora)、[コスト方針](development-guidelines.md#8-コスト方針)）
   - **使われていないのにタスクが動いている**: 使われていないアラーム（`quiz-app-dev-quiz-service-idle`）の状態と、Step Functions のログ（`/aws/vendedlogs/states/quiz-app-dev-quiz-service-idle-stop`）を見る。
     起こしたまま一度も使っていなければ、アラームの状態が変わらないため止まらない。深夜 2:00 の停止が止める
   - **API が叩かれ続けている**: ダッシュボードの要求の数。API 全体（20 件/秒）と利用者ごと（5 件/秒）の上限は付いている。利用者ごとに超えたものは、アプリのログの「流量の上限を超えました」に `user.id` 付きで出る
3. すぐに止めるなら、タスクを止める。Aurora は接続がなくなれば止まる

   ```bash
   aws ecs update-service --cluster quiz-app-dev --service quiz-service --desired-count 0
   ```

## secret を push してしまった

開発ガイドラインの[secret の検出](development-guidelines.md#secret-の検出)に従う。**まず値を無効にする。** 履歴は書き換えない。

## 訓練

手順が古くなっていないかは、dev で障害を起こして確かめる。手順を変えたとき、通知の仕組みを変えたときに流す。

### 通知が DLQ に入る

読めない版（99）のイベントを、カスタムバスに 1 件送る。受け手は版を確かめた時点で失敗し、Slack には送らない。
約 3 分で DLQ に入り、5 分ほどでアラームのメールが届く。そこから[通知が DLQ に入った](#通知が-dlq-に入った)の手順で中身を確かめ、DLQ を空にする（訓練のイベントは流し直さない）。

```bash
aws events put-events --entries '[{
  "EventBusName": "quiz-app-dev", "Source": "quiz-app.quiz-service", "DetailType": "QuizPublished",
  "Detail": "{\"eventId\":\"00000000-0000-4000-8000-0000000000aa\",\"version\":99,\"tenant\":{\"id\":\"00000000-0000-4000-8000-0000000000dd\",\"slug\":\"drill\",\"name\":\"訓練\"}}"
}]'
```

### 通知を流し直す

[通知をアーカイブから流し直す](#通知をアーカイブから流し直す)の手順で、Slack に届いたことのあるイベントを含む範囲を流し直す。
Lambda のログで、すべて「処理済みか処理中のイベントのため、知らせません」になれば通っている。範囲は、Lambda のログの「知らせました」の時刻から選ぶ。
**訓練のイベント（読めない版）を含む範囲は避ける。** もう一度 DLQ に入る。

## 障害の記録

利用者に影響したもの、手で戻したものは、Jira に課題を作って残す。誰かを責めるためではなく、同じことを繰り返さないため。

```
## 起きたこと
いつからいつまで、何が使えなかったか。どう気づいたか（アラーム、メール、利用者）

## 影響
誰に（全員、テナント、API）、どれくらい

## 原因
直接の原因と、それを許した理由

## 対応
止血にしたこと、直したこと（PR）

## 再発を防ぐ
アラーム、テスト、手順のどれを足すか。足したら、この Runbook も直す
```
