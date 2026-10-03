# 負荷試験

quiz-service がどれだけの利用に耐えるか、どこが先に詰まるかを、[k6](https://k6.io/) で測る（DEV-112）。
スクリプトは `tests/load/load-test.js` にある。

## 何を流すか

利用者がクイズを解くときの流れを、3 つのシナリオで同時に流す。

| シナリオ | 流れ | 利用者 |
| --- | --- | --- |
| `play` | 出題できるカテゴリ → 挑戦を始める（10 問、未回答を優先）→ 1 問ずつ回答 → 結果 | 一般ユーザー（1 VU に 1 人） |
| `browse` | 回答の履歴 → カテゴリ別の正答率 → ランキング | 一般ユーザー（1 VU に 1 人） |
| `admin` | カテゴリの一覧 → クイズの一覧 | 管理者 1 人 |

| プロファイル | 中身 | 回答の間隔 |
| --- | --- | --- |
| `smoke` | 各シナリオを 1 回ずつ。スクリプトと環境がつながっているかを確かめる | 0 秒 |
| `load` | `play` を 10 → 20 → 30 → 40 人、`browse` を 2 → 4 → 6 → 8 人に増やす。各段 3 分（計 14 分） | 1 秒 |
| `stress` | `load` と同じ人数で、間隔を詰める。各段 2 分（計 10 分） | 0.3 秒 |

- **API Gateway を直接叩く。** web の proxy（Amplify）は通さない。測りたいのは quiz-service と Aurora
- **専用のテナント `load` で流す**（シード `R__load_data.sql`）。カテゴリ 3 つ × 難易度 3 つ × 20 問 = 180 問。
  回答と履歴は試験のたびに増える。実際の利用でも増えていくものなので、消さない
- **利用者は 51 人**（一般ユーザー 50 人と管理者 1 人）。利用者ごとに流量の上限（5 件/秒、バースト 30）があり、1 人では負荷にならない。
  VU ごとに別の利用者を割り当て、同じ利用者を同時に使わない。利用者は Terraform（`envs/dev` の `load_user_emails`）が作り、シードが同じアドレスで所属を用意している
- トークンの取得と、利用者の結び付け（最初の要求）は、試験の前の準備（`setup`）で済ませる。計測には入れない。止まっている Aurora もここで起きる

## 流し方

**昼に流す。** 2:00〜8:00 は ECS のタスクが止まっている。

1. API 全体の流量の上限（20 件/秒）を、試験の間だけ上げる。`stress` の 40 人は、上限の 5 件/秒に近い速さで叩く

   ```bash
   cd infra/terraform/envs/dev
   terraform apply -var 'api_throttling={rate_limit=300,burst_limit=600}'
   ```

2. 値を Terraform の出力から読む

   ```bash
   export BASE_URL="$(terraform output -raw quiz_service_url)"
   export AUTH_CLIENT_ID="$(terraform output -raw auth_client_id)"
   export AUTH_CLIENT_SECRET="$(terraform output -raw auth_client_secret)"
   export LOAD_USER_PASSWORD="$(terraform output -raw load_user_password)"
   cd -
   ```

3. つながるかを確かめてから、流す。結果の HTML（時系列のグラフ）は `tests/load/results/` に残る（Git の管理外）

   ```bash
   k6 run -e PROFILE=smoke tests/load/load-test.js
   K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=tests/load/results/load-$(date +%Y%m%d-%H%M).html \
     k6 run -e PROFILE=load tests/load/load-test.js
   ```

4. 流している間と後に、[ダッシュボード](development-guidelines.md#ダッシュボード)で、タスクの CPU とメモリ、Aurora の ACU と接続の数を見る。
   API ごとの時間は、Logs Insights で見る（[ログ](development-guidelines.md#ログquiz-service)の「遅い API」）

5. **上限を戻す。** `-var` を付けずに apply する

   ```bash
   cd infra/terraform/envs/dev
   terraform apply
   ```

k6 は `.mise.toml` で版を固定している（`mise install` で入る）。

### 費用

1 回の試験（`load` と `stress`、計 25 分ほど）で、合わせて 0.2 ドルほど。

| 費用 | 見積もり |
| --- | --- |
| Aurora の ACU | 最大 2 ACU × 0.5 時間 × 0.15 ドル = 0.15 ドル |
| API Gateway | 10 万件ほど × 100 万件あたり 1.29 ドル = 0.13 ドル以下 |
| CloudWatch Logs | 要求ごとに 1 行。数十 MB × GB あたり 0.76 ドル = 0.05 ドル以下 |
| Cognito | 試験の利用者 51 人。Essentials は月 1 万人まで無料 |

ECS のタスクは数を増やさないので、ふだんと同じ。
