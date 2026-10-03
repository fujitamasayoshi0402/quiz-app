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

3. つながるかを確かめてから、流す。結果の HTML（時系列のグラフ）は `tests/load/results/` に残る（Git の管理外）。
   **`caffeinate -i` を付けて、Mac をスリープさせない。** 途中でスリープすると k6 が止まり、戻ったときに待ち時間切れの失敗がまとめて出る

   ```bash
   k6 run -e PROFILE=smoke tests/load/load-test.js
   K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=tests/load/results/load-$(date +%Y%m%d-%H%M).html \
     caffeinate -i k6 run -e PROFILE=load tests/load/load-test.js
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

## 結果

dev（タスク 1 つ・0.5 vCPU / 1 GB、Aurora 最大 2 ACU、接続プール 5）で測った。

### チューニングの前

**エラーは出なかった。** `stress` の最後の段（`play` 40 人、約 100〜114 件/秒）で、Aurora とタスクの CPU がともに上限に近づいた。

| 試験 | 要求 | 失敗 | k6 の p50 / p95 / p99 | 最大の速さ |
| --- | --- | --- | --- | --- |
| `load` | 24,880 | 0 | 58 / 240 / 369 ms | 約 45 件/秒 |
| `stress` | 46,648 | 0 | 64 / 232 / 302 ms | 約 114 件/秒 |

k6 の時間には、手元から東京リージョンまでの往復が入る。アプリの中の時間は、Logs Insights（`http.duration_ms`）で見た。

`stress` の 1 分ごとの値（ダッシュボード）。

| 件/秒 | API Gateway の p95 | タスクの CPU | Aurora の ACU | DB の CPU | DB の接続 |
| --- | --- | --- | --- | --- | --- |
| 32〜35 | 66〜69 ms | 25〜30% | 1.5 | 53〜56% | 5 |
| 53〜71 | 73〜76 ms | 46〜53% | 2.0（上限） | 98〜100% | 5 |
| 95〜114 | 91〜144 ms | 75〜97% | 2.0（上限） | 100% | 5 |

`stress` の API ごとの時間（アプリの中。ms）。

| API | 件数 | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| `GET /admin/quizzes`（60 問） | 251 | 144 | 228 | 303 |
| `POST /play/attempts/{id}/complete` | 3,115 | 70 | 145 | 202 |
| `POST /play/attempts` | 3,109 | 70 | 141 | 197 |
| `GET /play/history/attempts` | 1,566 | 33 | 99 | 161 |
| `POST /play/attempts/{id}/answers` | 31,129 | 29 | 99 | 156 |
| `GET /play/history/categories` | 1,565 | 20 | 93 | 167 |
| `GET /play/ranking` | 1,565 | 20 | 91 | 133 |
| `GET /play/categories` | 3,107 | 13 | 75 | 125 |

**先に詰まるのは Aurora。** 約 70 件/秒で 2 ACU を使い切り、DB の CPU が 100% になった。タスクの CPU が 100% に近づくのは、その先（約 100 件/秒）。
接続の数は、ずっと接続プールの上限（5）だった。DB の CPU が先に尽きているため、プールを大きくしても速くはならない。

### 詰まったところ: 選択肢の N+1

1 回の要求で送る SQL の本数を数えると（`QueryCountApiTest`）、**クイズを何件も読む API が、選択肢をクイズ 1 件ごとに問い合わせていた。**
Spring Data JDBC の `@Query` で集約を読むと、子（選択肢）を親 1 件ごとに読む。

| API | 前（20 問のとき） | 後 |
| --- | --- | --- |
| `POST /play/attempts` | 29（うち選択肢 20） | 10 |
| `POST /play/attempts/{id}/complete` | 30（うち選択肢 20） | 12 |
| `GET /admin/quizzes` | 25（うち選択肢 20） | 6 |
| `POST /play/attempts/{id}/answers` | 10 | 10 |

- 挑戦を始めるときは、出題する 10 問ではなく、候補（難易度を選ぶと 20 問、カテゴリだけなら 60 問）すべての選択肢を読んでいた
- 直し方: 何件も読む 3 つの読み込みを `QuizListJdbc` に移し、選択肢をクイズの ID の一覧で 1 本の SQL で読む。1 件の読み込みと保存は、集約のまま Spring Data JDBC に任せる
- `QueryCountApiTest` が、2 問のときと 12 問のときで本数が同じであることを確かめる。元の読み方に戻すと落ちる

どの要求にも、テナント・利用者・所属を引く 3 本と、テナントの設定（`set_config`）の 1 本が付く。回答では 10 本のうち 4 本にあたる。
減らすには利用者と所属をタスクのメモリに持つことになり、所属を外したときに効くまでの遅れが生まれる。いまは採らない。

## ADR-0023 の検証事項への答え

[ADR-0023](adr/0023-keep-answer-as-module-in-quiz-service.md) は、回答・採点が、クイズの管理や出題と違う負荷の特性を持つかを、負荷試験で確かめるとしていた。

**「分けたくなる条件」には当たらない。**

- 回答（`answers`）は要求の数では 7 割を占めるが、1 件は軽い（SQL 10 本、p95 99 ms）。重かったのは、quiz の側の読み方（選択肢の N+1）で、挑戦の開始・結果・管理の一覧に効いていた
- 先に尽きるのは、両方が共有する Aurora。answer だけを別のタスクに分けても、同じ DB の上限に当たる
- タスクの CPU は約 100 件/秒で上限に近づくが、quiz-service はステートレスで、タスクを増やせば足りる（ADR-0023 の C のとおり）
