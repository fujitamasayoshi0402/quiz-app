# Runbook

異常に気づいたときに、何を見て、どう戻すか。対象は dev 環境（prod は DEV-119 で作る）。
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

API Gateway が 503 を返している。送り先のタスクがない。

- 2:00〜8:00 は、夜間の停止で止まっている（[ECS](development-guidelines.md#ecsquiz-service)）。使うなら手で起動する。次の停止の時刻にまた止まる

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
gh workflow run deploy-dev.yml --ref develop
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
   gh workflow run deploy-dev.yml --ref develop -f backend=deploy -f frontend=skip
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

## Terraform

- **apply は、最新の develop から流す。** 古いブランチや worktree から流すと、先に入った別の変更が消える
- ロックが残った、SSO が途中で切れた: 開発ガイドラインの[インフラ（Terraform）](development-guidelines.md#インフラterraform)

## 費用が予算を超えそう

予算の通知（85%、100%、月末の予測）が届いたとき。

1. Cost Explorer で、タグ `Env` ごと、サービスごとに見る
2. よくある原因
   - **Aurora が一時停止しない**: ダッシュボードの ACU が 0 に戻らない。接続の数を見て、`pg_stat_activity` で誰がつないでいるかを確かめる（[Aurora](development-guidelines.md#aurora)、[コスト方針](development-guidelines.md#8-コスト方針)）
   - **タスクが夜も動いている**: 夜間の停止のスケジュールが失敗していないか。手で起動したまま、なら次の停止の時刻に止まる
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
