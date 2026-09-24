# ADR-0012: フロントは Amplify Hosting で配る

- ステータス: Accepted
- 決定日: 2026-09-24

## 背景と課題

Phase 2 で、web（Next.js）を AWS のどこで動かすかを決める。

web はサーバー側の実行環境を必ず要する。

- `/api` を proxy（`src/proxy.ts`）でバックエンドへ中継し、利用者の識別（`X-User-Id`）を付け直している。
  Phase 3 では、ここで Cognito のトークンを付ける
- 画面の一部は Server Components で、Cookie を読んで描き分けている
- 中継先は実行時の環境変数（`API_ORIGIN`）から読む
- standalone 出力のイメージ（`apps/web/Dockerfile`）がある。Next.js は 16 系、pnpm の monorepo

加えて、**Phase 3 まではスタブ認証である。** quiz-service は `X-User-Id` ヘッダをそのまま利用者とみなす。
quiz-service に外から直接届くと、ヘッダ 1 つで誰にでもなりすませる。
利用者の識別を付けてよいのは web の proxy だけ、という前提を AWS 上でも崩せない。

## 判断基準

- 常時かかる費用
- 書くインフラと、運用するものの少なさ
- いまの Next.js のバージョンと、proxy・実行時の環境変数が動く
- quiz-service へのなりすましの経路を塞げる
- マイグレーション → quiz-service → web の順にデプロイできる

## 検討した選択肢

### A: Amplify Hosting

ビルド・配信（CDN）・証明書まで揃う。リクエストがなければ費用はほぼかからない。
ブランチ単位のベーシック認証があり、dev のアクセス制限（DEV-46）に使える。

- **公式にサポートする Next.js は 15 まで**。16 の App Router でも動いたという報告はあるが、
  pnpm の monorepo でビルドが失敗する報告もある
- SSR の実行時には環境変数が渡らない。ビルドの中で `.env.production` に書き出して埋め込む。
  環境ごとにビルドが分かれ、同じ成果物を使い回せない
- SSR の実行環境は VPC の外にある。quiz-service を ALB で公開する必要がある
- 既定では Amplify が Git の push を見てビルドする。GitHub Actions からのデプロイと経路が分かれる
- レスポンスのストリーミングに対応していない

### B: ECS（ALB 配下）

quiz-service と同じく ECS のサービスとして動かす。ALB は web にだけ向け、quiz-service は ALB に載せない。

- quiz-service を VPC の外に出さずに済む。なりすましの経路が構成で塞がる
- 既存のイメージがそのまま動く。デプロイは quiz-service と同じ経路（ECR / ECS）
- web のタスクが常時動く。夜間停止を入れても、A より月 5〜10 ドル高い

### C: ECS + CloudFront

B の前に CloudFront を置き、静的ファイルをエッジから配る。WAF を付ける場所にもなる。
費用は B とほぼ同じで、構成要素が最も多い。

### 費用の比較

ALB はどの案でも 1 台要る（A でも quiz-service の公開に要る）ので、差は web の実行分だけになる。
東京リージョンの公開価格からの概算。

| 案 | 月額の目安 | 前提 |
| --- | --- | --- |
| A | 1〜2 ドル | ビルド 1 回 3〜4 分（検証では約 3 分）× 月 30 回。SSR のリクエストと実行時間は、dev の量ではほぼ 0 |
| B | 約 11 ドル（夜間停止で約 6 ドル） | 0.25 vCPU / 0.5 GB を 1 タスク |
| C | B とほぼ同じ | CloudFront は無料枠に収まる |

## 決定

**A を採用する。** 使われない時間の費用がほぼかからず、CDN・証明書・ベーシック認証を自分で組まずに済む。
quiz-service は ECS で動かしているため、コンテナの運用は quiz-service の側で扱える。

A の欠点には、次のように対処する。

| 欠点 | 対処 |
| --- | --- |
| quiz-service を公開する | スタブ認証の間は、web の proxy だけが知る秘密のヘッダを付け、持たない要求は ALB で拒否する。Phase 3 で JWT を検証するようになったら外せる |
| デプロイの経路が分かれる | Amplify の自動ビルドは止める。GitHub Actions から、マイグレーションと quiz-service のデプロイの後に Amplify のビルドを起動する |
| 環境変数がビルドで固定される | 受け入れる。環境ごとに Amplify のブランチを分け、それぞれでビルドする。web のイメージはローカル（`docker compose`）用に残す |
| Next.js 16 が公式サポートの外 | 採用の前に動かして確かめた（検証事項）。Next.js を上げるたびに、dev へのデプロイで確かめる |

B は、費用の差に見合うだけの利点がいまはない。C は B に後から足せるものであり、B を採らない以上は検討しない。

## 結果

### 良い影響

- web の費用が、使った分だけになる。夜間停止の仕組みも web には要らない
- CDN・証明書・ブランチごとの配信を、自分で組まずに使える
- dev のアクセス制限を、ベーシック認証で始められる

### 悪い影響・受け入れるリスク

- AWS 上の web は、ローカルのイメージと別の仕組みでビルドされる。ローカルで動いても AWS で壊れることがある
- quiz-service を公開する。秘密のヘッダを漏らすと、スタブ認証の間はなりすませる
- Next.js を上げるたびに、Amplify が対応しているかを確かめる必要がある
- ストリーミングを前提にした画面（`loading.tsx` や `Suspense` による段階的な表示）は、まとめて返る

### 緩和策

- Next.js を上げる PR では、dev へのデプロイが通ることを確かめてからマージする
- 秘密のヘッダの値は Secrets Manager に置き、定期的に入れ替えられるようにする
- 使い続けられなくなったら B に移る。web のイメージはすでにあり、移るのは配信の場所だけ

## 検証事項

採用の前に、検証用の Amplify アプリを作り、develop のコードをそのままビルドして確かめた。
中継先には、受けた要求をそのまま返す Lambda を置いた。

| 確かめたこと | 結果 |
| --- | --- |
| Next.js 16 と pnpm の monorepo でビルドできる | **条件付きで通った**（16.3.6）。pnpm の既定の配置（シンボリックリンク）では、Amplify が `node_modules` に `next` を見つけられず失敗する。ビルドの中でだけ `nodeLinker: hoisted` にすると通る |
| `proxy.ts` が外部の URL へ中継し、`X-User-Id` を付け直す | 通った。ブラウザが付けた値は捨てられ、Cookie の利用者に置き換わる。Cookie が無ければ付かない |
| `API_ORIGIN` を `.env.production` 経由で実行時に読める | 通った |
| ベーシック認証をかけても、proxy と画面が動く | 通った。ログイン（Server Action で Cookie を置く）から API の呼び出しまで動く。ベーシック認証の `Authorization` は、Amplify が外してから中継する |

hoisted は、コマンドの引数ではなく `pnpm-workspace.yaml` に追記して指定する。
引数で渡すと、`pnpm --filter web build` の前の検査で既定の配置に入れ直される。
リポジトリの設定は変えない。ローカルでは、宣言していない依存を読めない既定の配置のままにしておきたいため。

構築の課題で決めること。

- 秘密のヘッダを web へ渡す方法（ビルド時の環境変数か、SSR の実行ロールで Secrets Manager から読むか）
- ビルドの設定（`amplify.yml`）をリポジトリに置くか、Terraform で持つか
- GitHub との接続に使うトークンの扱い。接続にだけ使い、作成後は失効させる
- ネットワークの SecurityGroup（DEV-42）は、web を ECS に置く前提で「ALB → web → quiz-service」になっている。
  web の SG を外し、ALB から quiz-service へ直接届ける形に直す
