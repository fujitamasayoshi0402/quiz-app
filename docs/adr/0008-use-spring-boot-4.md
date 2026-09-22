# ADR-0008: Spring Boot 4 を採用する

- ステータス: Accepted
- 決定日: 2026-09-22

## 背景と課題

[ADR-0002](0002-use-kotlin-and-spring-boot.md) で「Kotlin + Spring Boot 3 (Java 21)」を採用した。
しかし実装に着手する時点で Spring Boot 4.1 がリリースされており、状況が変わっている。

Spring Boot のマイナーバージョンは OSS での保守期間が約 1 年で、それ以降の修正は商用サポートの対象になる。
3.x 系はその期間を過ぎているか、まもなく過ぎる。

**まだ 1 行もコードを書いていないこの段階でしか、バージョンの選び直しは安く済まない。**
実装が進んだ後のメジャーバージョン移行は、依存ライブラリの対応待ちと設定の書き換えを伴う。

## 判断基準

- 無償で脆弱性修正を受けられること
- 実装時に参照できる情報があること
- 数年単位で使い続けられること

## 検討した選択肢

### 選択肢 A: Spring Boot 3.x を使う（ADR-0002 のまま）

書籍・記事・Stack Overflow の情報が最も多い。
実装中に詰まったときに解決しやすいのは確かな利点である。

しかし **OSS での保守期間を過ぎたバージョンで新規開発を始めることになる**。
脆弱性が見つかっても、無償では修正版が提供されない。
個人プロジェクトとはいえ、公開するアプリケーションでこの状態から始めるのは筋が悪い。

また、いずれ 4.x へ上げるなら、コードが増える前に済ませたほうが安い。

### 選択肢 B: Spring Boot 4.1 を使う

最新のリリース。保守期間が最も長く残っている。

代償として情報が少ない。3.x 向けの記事がそのまま使えない場面があり、
Jakarta EE のバージョン差や設定項目の変更を自分で調べることになる。

一方で、Spring Boot は**バージョン間で「標準的な作り方」が大きく変わるフレームワークではない**。
ADR-0002 で重視した「規約が確立していること」という性質は 4.x でも変わらない。

## 決定

**選択肢 B を採用する。Spring Boot 4.1 系を使う。**

依存のバージョンは Spring Boot の BOM が管理するものに合わせる。

| 依存 | バージョン | 備考 |
| --- | --- | --- |
| Spring Boot | 4.1.1 | |
| Kotlin | 2.3.21 | BOM が管理する版に合わせる。単体の最新は 2.4 系だが、組み合わせが検証されていない |
| Flyway | 12.4.0 | BOM 管理 |
| PostgreSQL JDBC | 42.7.13 | BOM 管理 |
| JUnit | 6.0.3 | BOM 管理 |
| Testcontainers | 2.0.5 | BOM 管理 |

Kotlin は BOM が指すバージョンを使う。新しいほうが良いとは限らず、
**Spring Boot が検証していない組み合わせを自分で踏み抜く理由がない**ためである。

### ADR-0002 との関係

ADR-0002 を Superseded にしない。
あの決定の本質は「Java ではなく Kotlin を使う」「独自構成ではなく Spring Boot に乗る」ことであり、
その判断は有効なまま残る。本 ADR はバージョンの選択について補う。

## 結果

### 良い影響

- 保守期間が長く、脆弱性修正を無償で受けられる
- メジャーバージョン移行をコードが増える前に済ませられる
- 新しいバージョンで設計する経験が残る

### 悪い影響・受け入れるリスク

- **情報が少ない。** 3.x 向けの記事を読み替える手間が常時かかる
- サードパーティのライブラリが 4.x に未対応の場合がある。採用前に確認が要る
- 生成 AI の学習データも 3.x が中心のため、提案されたコードが古い書き方になることがある

### 緩和策

- 依存を追加するときは Spring Boot 4 対応を確認してから入れる
- 公式ドキュメントを一次情報として扱う。記事は対象バージョンを確認してから参照する
- 3.x との差分で詰まった箇所はクイズの題材として記録する

## 検証事項

- ~~Gradle 9.7.1 と Kotlin 2.3.21 プラグインの組み合わせ~~ → DEV-18 で動作を確認
- ~~Flyway 12 系でのマイグレーション記法~~ → DEV-18 で確認。記法の変更はなかった
- Testcontainers 2 系での統合テスト（DEV-27）
- Fargate の最小タスクサイズでの動作。ADR-0002 の検証事項をそのまま引き継ぐ（Phase 2）

### DEV-18 で判明した 3.x との差分

**autoconfigure がモジュール分割されている。**
3.x では `flyway-core` を依存に加えるだけで自動設定が効いたが、4.x では効かない。
`spring-boot-starter-flyway` が別途必要で、入れ忘れると**エラーも警告も出ないまま
マイグレーションが実行されない**。起動には成功するため気づきにくい。

**Testcontainers の artifact 名が変わっている。**
Spring Boot 4.1.1 の BOM は Testcontainers 2.0.5 を指すが、1.x の `org.testcontainers:postgresql` は
2.x に存在しない。2.x では `org.testcontainers:testcontainers-postgresql` のように
プレフィックスが付く（DEV-34 で判明）。コンテナのクラスも
`org.testcontainers.containers` から `org.testcontainers.postgresql` へ移っている。

**Jackson が 3 系になり、groupId とパッケージが変わっている。**
`com.fasterxml.jackson.core` → `tools.jackson.core`、
`com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper`。
記事やサンプルの import がそのままでは通らない（DEV-19 で判明）。

**MockMvc のテスト支援が spring-boot-starter-test から分離されている。**
`spring-boot-starter-webmvc-test` が別途必要で、`AutoConfigureMockMvc` のパッケージも
`org.springframework.boot.test.autoconfigure.web.servlet` から
`org.springframework.boot.webmvc.test.autoconfigure` へ移っている（DEV-19 で判明）。

同種の差分が他の機能でも起こりうる。依存を追加したら、
**実際にその機能が動いていることを確認する**まで完了としないほうがよい。

## 関連

- [ADR-0002](0002-use-kotlin-and-spring-boot.md): Kotlin + Spring Boot を採用する決定。本 ADR はそのバージョン選択を補う
