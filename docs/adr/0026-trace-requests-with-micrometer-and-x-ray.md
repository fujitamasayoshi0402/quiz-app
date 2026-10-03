# ADR-0026: 分散トレースは Micrometer Tracing（OpenTelemetry）で取り、同じタスクのコレクタから X-Ray へ送る。イベントは Outbox に文脈を残してつなぐ

- ステータス: Accepted
- 決定日: 2026-10-04

## 背景と課題

1 回の操作が、web の proxy → API Gateway → quiz-service → Aurora → EventBridge → notification-service（Lambda）とまたぐ。
いまはログ（要求の ID、テナント、利用者）とアラームで追っているが、**どこで遅いか、どこで失敗したか**を 1 本の流れでは見られない。
止まっている Aurora の復帰を待った時間も、要求の時間に埋もれている。

技術スタックに OpenTelemetry を挙げているが、まだ入れていない。決めること。

- 送り先を、マネージドに寄せるか、自前で持つか
- quiz-service からの取り出し方（Java エージェントか、Spring Boot に組み込みの Micrometer Tracing か）
- 非同期のイベント（Outbox → EventBridge → Lambda）をまたいで追うか、どうつなぐか
- web（Next.js の SSR）をどこまで入れるか
- 費用とメモリ、記録する割合

## 判断基準

- **常駐するものを増やさない。** 予算は月 30 ドル。止められない費用を増やさない
- **Aurora の一時停止（min 0 ACU）を妨げない。** トレースのために DB へ接続しない
- タスクのメモリ（1 GB）に収まる。いまの使用量は最大 44%
- ログとトレースを行き来できる（同じ trace ID がログにも載る）
- **外から記録の量を増やされない。** API は API Gateway から誰でも呼べる
- ログと同じく、メールアドレス、トークン、問題文をトレースに載せない

## 検討した選択肢

### 送り先

| 選択肢 | 良い点 | 悪い点 |
| --- | --- | --- |
| **A: AWS X-Ray** | マネージド。月 10 万件の記録まで無料。Lambda はアクティブトレースを有効にするだけで入る。EventBridge がトレースヘッダを Lambda に渡す | AWS に閉じる。画面は CloudWatch のコンソール |
| B: Jaeger などを自分で置く | 送り先を選べ、仕組みが全部見える | 常駐のコンテナとストレージが要り、止められない費用が増える |
| C: SaaS（Grafana Cloud など） | 画面と検索が強い。無料枠がある | 外部のアカウントと API キーの管理が増える。Lambda のつなぎ方を自分で用意する |

X-Ray には、トレースを CloudWatch Logs に入れて検索できるようにする Transaction Search もある。
アカウント全体の設定を変え、記録の量に応じて CloudWatch Logs の料金がかかる。いまは従来の X-Ray（無料枠の中）で足りるため使わない。prod で量が増えたら見直す。

### quiz-service からの取り出し方

| 選択肢 | 良い点 | 悪い点 |
| --- | --- | --- |
| D: ADOT の Java エージェント | コードを変えずに HTTP・JDBC・AWS SDK を取る。コレクタなしで X-Ray へ送れる | 起動が数秒延び、メモリも 100 MB 前後増える。中で何が起きているかが見えにくい。Spring の構成とは別の場所で設定する |
| **E: Micrometer Tracing（Spring Boot に組み込み）+ 同じタスクのコレクタ** | Spring Boot の設定と同じ場所で扱える。ログへの trace ID は Spring が載せる。文脈の受け渡しを自分で書くので、仕組みが分かる | コレクタのコンテナ（128 MB まで）が増える。AWS SDK の呼び出しは自動では区間にならない |
| F: Micrometer Tracing から X-Ray の OTLP の口へ直接 | コレクタが要らない | 要求に AWS の署名（SigV4）が要り、Spring の OTLP の送り手は署名しない。Transaction Search が前提 |

コレクタは同じタスクの中に置く。タスクの数もパブリック IP も増えず、費用は変わらない（Fargate はタスクの大きさで課金する）。

### イベントをまたぐ方法

| 選択肢 | 良い点 | 悪い点 |
| --- | --- | --- |
| **G: Outbox の行に `traceparent` を残し、`PutEvents` の `TraceHeader` に渡す。Lambda はアクティブトレース** | Lambda のコードを変えない。起動も遅くならない。拾い直しで遅れて送ったものも、元の要求につながる | Lambda の中（Slack への送信など）は区間にならず、関数の 1 区間として見える |
| H: Lambda に ADOT のレイヤーを付ける | Lambda の中も区間にできる | Java の Lambda の起動が数秒延びる。G の文脈の受け渡しは別に要る |
| I: つながない | 何も足さない | 「クイズを公開したのに通知が来ない」を 1 本で追えない |

### web の範囲

| 選択肢 | 良い点 | 悪い点 |
| --- | --- | --- |
| **J: proxy で `traceparent` を作って API に渡すだけ** | 小さい。web が trace ID を決めるので、SSR のログにも同じ ID を出せる | web の中の時間はトレースに載らない |
| K: web の区間も X-Ray に送る | 端から端まで 1 本で見える | Amplify の SSR から送るには、計算用のロールと署名が要る。Next.js の計装も入れる |

## 決定

**A + E + G + J。** X-Ray をマネージドの送り先にし、quiz-service は Micrometer Tracing（OpenTelemetry）で取り、同じタスクの ADOT コレクタから送る。

```
web の proxy（traceparent を作る）→ API Gateway（そのまま通す）→ quiz-service（Micrometer Tracing。HTTP、JDBC の接続と問い合わせ）
  → OTLP → 同じタスクの ADOT コレクタ → X-Ray
quiz-service が Outbox に書く（traceparent も残す）→ 送るとき PutEvents の TraceHeader に渡す → EventBridge → Lambda（アクティブトレース）→ X-Ray
```

- **trace ID は、先頭 8 桁を作った時刻（エポック秒）にする。** X-Ray は、先頭が時刻として読めない trace ID を捨てることがある。
  web の proxy（`apps/web/src/lib/trace-context.ts`）と quiz-service（`XrayCompatibleIdGenerator`）が同じ形で作る。W3C の形としても正しい
- **proxy は `traceparent` を毎回作り直す。** ブラウザから届いたものは使わない
- **記録するかは quiz-service が trace ID から決める**（`trace-id-ratio`）。呼び出し元の「記録する」の印には従わない。
  API は誰でも呼べるため、印に従うと、外から記録の量と費用を増やされる。Lambda は、quiz-service の判定をトレースヘッダの `Sampled` で受け取る。dev はすべて記録する
- JDBC の区間は、datasource-micrometer で取る。**接続を待った時間（止まっている Aurora の復帰を含む）が、接続の区間に出る。** SQL の文は載せ、値は載せない
- トレースにしないもの: コンテナのヘルスチェック、定期的な処理（Outbox の拾い直し、データの整合性の確認）、要求の外で動いた DB の操作。
  1 分ごとに、何もしないトレースが積もるのを避ける。拾い直しで送ったイベントは、行に残した文脈で元の要求につながる
- ログ（JSON）の各行に `trace.id` と `span.id` を載せる。要求の ID（API Gateway の `requestId`）と並ぶので、アクセスログ → アプリのログ → トレースとたどれる
- コレクタは止まってもタスクを止めない（`essential = false`）。トレースが欠けるだけで、アプリは動き続ける
- メトリクスとログは OTLP で送らない。いまの CloudWatch の仕組み（ログのメトリクスフィルタ）のまま

## 結果

良くなること。

- クイズの公開から通知までを、1 本のトレースで追える。Lambda の区間は、イベントを書いた要求の下にぶら下がる
- 遅い要求で、DB の接続待ち（Aurora の復帰）と問い合わせと、それ以外を分けて見られる
- ログから trace ID でトレースへ、トレースから trace ID でログへ行き来できる

受け入れること。

- web の中の時間は載らない。必要になったら K に進む
- AWS SDK の呼び出し（S3、SSM、EventBridge）は区間にならない。OpenTelemetry の AWS SDK の計装を足せば取れる
- Lambda の中の処理は 1 区間に見える
- タスクのメモリにコレクタの分（上限 128 MB）が加わる
- 記録の量は、API Gateway の流量の制限が上限になる。最悪のときの費用は、API Gateway の費用と同じ程度に膨らみうる。予算のアラートで気づく

費用（dev）。

| 項目 | 月額 |
| --- | --- |
| X-Ray の記録 | 月 10 万件まで無料。dev の量では 0 |
| コレクタ | 同じタスクの中。増えない |
| Lambda のアクティブトレース | X-Ray の記録の数に入る |
