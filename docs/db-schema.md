# DB スキーマ設計

[ドメインモデル](domain-model.md) をテーブル定義に落としたものです。
[Flyway のマイグレーション](../services/quiz-service/)（DEV-18）はこの設計に従って書きます。

前提となる決定:
[ADR-0006 マルチテナント](adr/0006-row-level-multi-tenancy.md) /
[ADR-0007 論理削除](adr/0007-soft-delete-master-data.md) /
[ADR-0004 段階的なサービス分割](adr/0004-split-services-incrementally.md)

---

## 全体方針

| 項目 | 決定 |
| --- | --- |
| 主キー | `uuid`（`gen_random_uuid()`） |
| 日時 | `timestamptz`。アプリケーションは UTC で扱う |
| 文字列 | `text`。長さ制限は `CHECK` 制約で表現する |
| 命名 | テーブルは複数形のスネークケース、カラムは単数形 |
| 共通カラム | `created_at` / `updated_at`、論理削除の対象には `deleted_at` |

### 主キーに UUID を使う理由

ID は URL に現れます（`/t/{slug}/admin/quizzes/{id}`）。
連番だと、**他テナントのリソース数やサービス全体の規模が外部から推測できます**。
`/quizzes/1` から `/quizzes/2` を試す行為も誘発します（RLS で阻止はしますが、試させないほうがよい）。

インデックスが大きくなり挿入順序も保たれませんが、想定する規模では問題になりません。

### 文字列長

`varchar(n)` ではなく `text` + `CHECK` を使います。
PostgreSQL では両者の性能差がなく、`CHECK` のほうが上限変更が容易なためです。

### updated_at は DB トリガーで更新する

アプリケーション層（JPA の `@LastModifiedDate` など）ではなく、トリガーで更新します。

```sql
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

更新経路がアプリケーションだけとは限らないためです。
整合性チェックやクイズ生成のバッチが SQL を直接実行する場合、
アプリケーション層の実装では **`updated_at` が古いまま残ります**。
「最終更新日を見て処理対象を決めるバッチ」を後から作ったとき、そこが狂うと検知が効きません。

トリガーはマイグレーションでの管理とテストでの再現が必要になりますが、
更新経路が増えても漏れない利点がそれを上回ると判断しました。

---

## スキーマ分割

サービス境界をスキーマで表現します（[ADR-0004](adr/0004-split-services-incrementally.md)）。

| スキーマ | テーブル | 将来の分離先 |
| --- | --- | --- |
| `core` | `tenants` / `users` / `tenant_members` | 分離しない（共通） |
| `quiz` | `categories` / `difficulties` / `quizzes` / `choices` | quiz-service |
| `answer` | `attempts` / `attempt_quizzes` / `answers` | answer-service |

### スキーマをまたぐ外部キーを貼らない

`answer.answers` は `quiz.quizzes` を参照しますが、**外部キー制約は設けません。**

制約を貼れば整合性は強くなります。しかし answer-service を別プロセス・別データベースに分離するとき、
その制約が必ず障害になります。ADR-0004 で「モジュールをまたぐ結合を禁止する」と決めた趣旨は、
分割時の作業を小さく保つことにあり、外部キーも同じ理由で避けます。

代わりに、回答時にクイズを取得する処理の中で存在を確認します。
採点にはクイズの内容が必要なので、**確認は自然に行われます**。

`core` への参照（`tenant_id` / `user_id`）は外部キーを貼ります。共通スキーマは分離しないためです。

---

## テーブル定義

### core.tenants

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK, default `gen_random_uuid()` |
| `slug` | text | NOT NULL, `CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$')` |
| `name` | text | NOT NULL, `CHECK (length(name) BETWEEN 1 AND 100)` |
| `visibility` | text | NOT NULL, default `'private'`, `CHECK (visibility IN ('private','public'))` |
| `created_at` | timestamptz | NOT NULL, default `now()` |
| `updated_at` | timestamptz | NOT NULL, default `now()` |
| `deleted_at` | timestamptz | NULL 可 |

```sql
CREATE UNIQUE INDEX tenants_slug_key ON core.tenants (slug) WHERE deleted_at IS NULL;
```

`slug` は URL に使うため、英小文字・数字・ハイフンに限定します。
削除したテナントの `slug` を再利用できるよう、ユニーク制約は生存行のみを対象にします。

### core.users

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `external_id` | text | NOT NULL, UNIQUE。Cognito のユーザー識別子（`sub`） |
| `display_name` | text | NOT NULL |
| `created_at` / `updated_at` | timestamptz | NOT NULL |

Phase 1 のスタブ認証では、`external_id` に任意の文字列を入れて動かします。
Phase 3 で Cognito の `sub` に置き換えますが、**カラムの意味は変わらないため移行はデータの入れ替えだけで済みます。**

パスワードやパスキーの情報はここに持ちません。認証は Cognito に委ねます（[ADR-0005](adr/0005-use-cognito-passkeys.md)）。

### core.tenant_members

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL, FK → `core.tenants(id)` |
| `user_id` | uuid | NOT NULL, FK → `core.users(id)` |
| `role` | text | NOT NULL, `CHECK (role IN ('admin','member'))` |
| `created_at` / `updated_at` | timestamptz | NOT NULL |
| `deleted_at` | timestamptz | NULL 可 |

```sql
CREATE UNIQUE INDEX tenant_members_unique
  ON core.tenant_members (tenant_id, user_id) WHERE deleted_at IS NULL;
CREATE INDEX tenant_members_user_idx ON core.tenant_members (user_id) WHERE deleted_at IS NULL;
```

`user_id` のインデックスは「所属テナント一覧」の取得に使います（`/` へのアクセス時）。

### quiz.categories

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL, FK → `core.tenants(id)` |
| `name` | text | NOT NULL, `CHECK (length(name) BETWEEN 1 AND 100)` |
| `description` | text | NULL 可 |
| `sort_order` | integer | NOT NULL, default 0 |
| `created_at` / `updated_at` | timestamptz | NOT NULL |
| `deleted_at` | timestamptz | NULL 可 |
| `deletion_batch_id` | uuid | NULL 可。`CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL)` |

```sql
CREATE UNIQUE INDEX categories_name_key
  ON quiz.categories (tenant_id, name) WHERE deleted_at IS NULL;

-- 複合外部キーの参照先として必要
ALTER TABLE quiz.categories ADD CONSTRAINT categories_id_tenant_key UNIQUE (id, tenant_id);

CREATE INDEX categories_list_idx
  ON quiz.categories (tenant_id, sort_order) WHERE deleted_at IS NULL;
```

### quiz.difficulties

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL |
| `category_id` | uuid | NOT NULL |
| `name` | text | NOT NULL |
| `level` | integer | NOT NULL, `CHECK (level >= 1)` |
| `sort_order` | integer | NOT NULL, default 0 |
| `description` | text | NULL 可 |
| `created_at` / `updated_at` | timestamptz | NOT NULL |
| `deleted_at` | timestamptz | NULL 可 |
| `deletion_batch_id` | uuid | NULL 可。`CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL)` |

```sql
-- カテゴリと同じテナントであることを DB が保証する
ALTER TABLE quiz.difficulties
  ADD CONSTRAINT difficulties_category_fk
  FOREIGN KEY (category_id, tenant_id) REFERENCES quiz.categories (id, tenant_id);

CREATE UNIQUE INDEX difficulties_name_key
  ON quiz.difficulties (category_id, name) WHERE deleted_at IS NULL;

-- 複合外部キーの参照先として必要
ALTER TABLE quiz.difficulties ADD CONSTRAINT difficulties_id_category_key UNIQUE (id, category_id);

-- カテゴリ横断のレベル検索用
CREATE INDEX difficulties_level_idx
  ON quiz.difficulties (tenant_id, level) WHERE deleted_at IS NULL;
```

**`level` にユニーク制約を付けません。** AWS のアソシエイト級（SAA / DVA / SOA）のように、
同じ難度帯に複数の種類が並ぶ体系を表現するためです（[ドメインモデル](domain-model.md#同じレベルに複数の難易度を持てる)）。

### quiz.quizzes

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL |
| `category_id` | uuid | NOT NULL |
| `difficulty_id` | uuid | NOT NULL |
| `question` | text | NOT NULL, `CHECK (length(question) BETWEEN 1 AND 2000)` |
| `explanation` | text | NOT NULL |
| `explanation_image_key` | text | NULL 可（Phase 4 で使う S3 キー） |
| `status` | text | NOT NULL, default `'draft'`, `CHECK (status IN ('draft','published'))` |
| `created_at` / `updated_at` | timestamptz | NOT NULL |
| `deleted_at` | timestamptz | NULL 可 |
| `deletion_batch_id` | uuid | NULL 可。`CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL)` |

```sql
-- カテゴリが同じテナントに属することを保証
ALTER TABLE quiz.quizzes
  ADD CONSTRAINT quizzes_category_fk
  FOREIGN KEY (category_id, tenant_id) REFERENCES quiz.categories (id, tenant_id);

-- 難易度がそのカテゴリに属することを保証
ALTER TABLE quiz.quizzes
  ADD CONSTRAINT quizzes_difficulty_fk
  FOREIGN KEY (difficulty_id, category_id) REFERENCES quiz.difficulties (id, category_id);

ALTER TABLE quiz.quizzes ADD CONSTRAINT quizzes_id_tenant_key UNIQUE (id, tenant_id);

-- 出題は公開済みのみを対象にするため、status を条件に含める
CREATE INDEX quizzes_filter_idx
  ON quiz.quizzes (tenant_id, category_id, difficulty_id)
  WHERE deleted_at IS NULL AND status = 'published';

-- 管理画面は下書きも含めて一覧する
CREATE INDEX quizzes_admin_idx
  ON quiz.quizzes (tenant_id, category_id) WHERE deleted_at IS NULL;
```

#### 下書きと公開

`status` を Phase 1 から持たせます。UI の作り込みは Phase 4 ですが、**カラムを後から追加すると、
それまでに書いた出題クエリすべてに「公開のみ」の条件を足して回ることになる**ためです。

- 既定は `draft`。作成した直後のクイズは出題されない
- 出題 API は `published` のみを対象にする
- 管理画面は両方を表示し、状態で絞り込めるようにする

#### 2 本の複合外部キーが守るもの

この 2 つの制約により、**矛盾した組み合わせをアプリケーションのバグでは作れなくなります。**

| 防がれる状態 | 効く制約 |
| --- | --- |
| 他テナントのカテゴリを参照するクイズ | `quizzes_category_fk` |
| 「カテゴリ: 認証認可 / 難易度: SAA」のような食い違い | `quizzes_difficulty_fk` |

アプリケーション側の検証（DEV-20）も残しますが、**それは利用者にわかりやすいエラーを返すためであり、
整合性の最後の砦は DB です。** 検証の実装を 1 箇所忘れても、データは壊れません。

### quiz.choices

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL |
| `quiz_id` | uuid | NOT NULL |
| `body` | text | NOT NULL, `CHECK (length(body) BETWEEN 1 AND 500)` |
| `is_correct` | boolean | NOT NULL, default false |
| `sort_order` | integer | NOT NULL |
| `created_at` / `updated_at` | timestamptz | NOT NULL |

```sql
ALTER TABLE quiz.choices
  ADD CONSTRAINT choices_quiz_fk
  FOREIGN KEY (quiz_id, tenant_id) REFERENCES quiz.quizzes (id, tenant_id) ON DELETE CASCADE;

-- 1 クイズにつき正解は 1 つまで
CREATE UNIQUE INDEX choices_single_correct ON quiz.choices (quiz_id) WHERE is_correct;

CREATE UNIQUE INDEX choices_order_key ON quiz.choices (quiz_id, sort_order);
```

#### 4 択の表現

`choices` に `is_correct` を持たせます。
`quizzes` に `correct_choice_id` を持たせる案は、`quizzes` と `choices` が相互に参照し合うため、
挿入の順序が複雑になります（クイズを作る → 選択肢を作る → クイズを更新して正解を指す）。

部分ユニークインデックスにより、**正解が 2 つある状態は DB が拒否します。**
一方「正解が 0 個」と「選択肢がちょうど 4 つ」は行数に関する制約のため、DB では表現できません。
これらはドメイン層で守ります（[ドメインモデル](domain-model.md#クイズ)）。

`choices` は論理削除しません。クイズに完全に従属し、単独で復活させる意味がないためです。
クイズを論理削除した場合、選択肢はそのまま残ります（親が見えないため実質的に隠れます）。

### answer.answers

| カラム | 型 | 制約 |
| --- | --- | --- |
| `id` | uuid | PK |
| `tenant_id` | uuid | NOT NULL |
| `attempt_id` | uuid | NOT NULL, FK → `answer.attempts(id, tenant_id)` |
| `user_id` | uuid | NOT NULL, FK → `core.users(id)` |
| `quiz_id` | uuid | NOT NULL（**外部キーなし**） |
| `choice_id` | uuid | NOT NULL（**外部キーなし**） |
| `is_correct` | boolean | NOT NULL |
| `answered_at` | timestamptz | NOT NULL, default `now()` |

```sql
-- 未回答優先の出題（DEV-21）で使う
CREATE INDEX answers_user_quiz_idx ON answer.answers (user_id, quiz_id);

-- 履歴・スコア集計用
CREATE INDEX answers_user_time_idx ON answer.answers (user_id, answered_at DESC);
```

挑戦（`attempts`）と出題リスト（`attempt_quizzes`）も同じく `tenant_id` を持ち、
子の `tenant_id` は複合外部キーで親の挑戦と一致させます。
中断中の挑戦は `(tenant_id, user_id)` の部分ユニークインデックスで、**テナントごとに 1 件まで**に制限します。

論理削除もしません。履歴そのものであり、削除する操作を設けないためです。

---

## RLS ポリシー

`quiz` / `answer` スキーマの全テーブルに適用します。

```sql
ALTER TABLE quiz.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.categories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.categories
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

### セッション変数は SET LOCAL 相当で設定する

アプリケーションはトランザクションの開始後に次を実行します。

```sql
SELECT set_config('app.tenant_id', ?, true)
```

第 3 引数の `true` が `SET LOCAL` 相当で、**トランザクションの終了時に PostgreSQL が値を破棄します**。
HikariCP は接続を使い回しますが、返却時のリセット処理は不要です。リセット漏れが構造的に起きません。

代償として、**トランザクションの外では設定できません**。読み取りだけの処理にもトランザクションが要ります。
トランザクション外で呼ばれた場合は例外にして、静かに失敗しないようにしています。

値は文字列連結ではなくプレースホルダで渡します。`set_config` は関数なので値をバインドできます。

> DEV-34 で、接続プールを 1 本に固定した状態でも前のトランザクションの値が残らないことを確認済みです。

### RLS 違反は「SQL 文法エラー」として現れる

PostgreSQL はポリシー違反を SQLState 42501（権限不足）で返します。
Spring はこれを `BadSqlGrammarException` に分類するため、**例外のメッセージから理由が消えます**。

```
PreparedStatementCallback; bad SQL grammar [INSERT INTO quiz.categories ...]
```

原因を知るには根本例外まで辿る必要があります。
エラー応答を組み立てるとき、この分類をそのまま「文法エラー」として扱わないよう注意します。

### 接続ロールを分ける

**スーパーユーザーは `FORCE ROW LEVEL SECURITY` を設定しても RLS をバイパスします。**
PostgreSQL の公式イメージでは `POSTGRES_USER` がスーパーユーザーとして作られるため、
そのまま接続すると **RLS を有効にしても一切機能しません**。

| ロール | 用途 | 属性 |
| --- | --- | --- |
| `quiz` | スキーマの所有者。Flyway による DDL を実行する | スーパーユーザー |
| `quiz_app` | アプリケーションの接続先 | `NOSUPERUSER` |

Spring Boot では `spring.datasource` に `quiz_app`、`spring.flyway.user` に `quiz` を指定して使い分けます。
`quiz` の認証情報を持つのは、マイグレーションだけを流す `migrate` プロファイルです。アプリは `quiz_app` しか持ちません
（[開発ガイドライン「マイグレーション」](development-guidelines.md#マイグレーション)）。

### `nullif` を挟む理由

セッション変数が未設定のときの挙動を揃えるためです。
一度も `SET` していなければ `NULL` が返りますが、`RESET` した後は**空文字列**が返ります。
素直に `::uuid` へキャストすると、後者だけが `invalid input syntax for type uuid` で失敗します。

`nullif` で `NULL` に揃えることで、どちらの場合も「0 件」で一貫します。
設定漏れを検知する仕組みはアプリケーション層に置きます。

### core.tenants と core.tenant_members には設定しない

この 2 つは**テナント境界の中にあるデータではなく、境界そのものを定義するテーブル**です。

所属テナント一覧の取得は、どのテナントで作業するかが決まる**前**に行われます。
この時点では `app.tenant_id` を設定できないため、ポリシーをかけると一覧が取れず、
複数テナントに所属するユーザーが最初の画面から先へ進めなくなります。

参照できる範囲はアプリケーション層で `user_id` により制御します。

### 論理削除の条件は含めない

ポリシーは `tenant_id` のみを見ます。`deleted_at IS NULL` を含めると、
**削除済み一覧を取得できなくなり、復活機能が作れません。**

削除済みが表示される事故は「表示の不整合」であり、テナント境界を越える情報漏洩とは重さが違います。
削除条件はリポジトリ層の共通機構で強制し、削除済みを取得する操作は復活機能専用のメソッドに限定します
（[ADR-0007](adr/0007-soft-delete-master-data.md)）。

### answer スキーマも本人ではなくテナントで絞る

ポリシーが見るのは `tenant_id` だけで、`user_id` は見ません。
ランキング（Phase 4）では同じテナントの他の利用者の回答を集計するため、
ポリシーで本人に限定すると機能が作れなくなります。

**本人の挑戦かどうかはアプリケーション層で判定します。** 他人の挑戦は「存在しない」として 404 を返します。

当初は `tenant_id` を持たせず、`user_id` だけで制御していました。
その結果、複数のテナントに所属する利用者では、テナント A の URL から B の挑戦を参照・終了できました（DEV-37）。
本人確認とテナント境界は別の軸であり、前者で後者を代替できません。

RLS は「アクセス先のテナント」で絞るもので、所属は問いません。
将来テナントを公開し、所属していない利用者が回答するようになっても矛盾しません。

---

## 連鎖削除と連鎖復活

カテゴリを削除すると、配下の難易度とクイズも論理削除します。

**アプリケーション層で実装します。** DB のトリガーは、マイグレーションでの管理が煩雑になり、
テストでの再現も難しいためです。削除の順序と対象をドメイン層に閉じ込め、呼び出し側が個別に消して回らないようにします。

### 「一緒に削除されたもの」の判別

復活時に、どの行を一緒に戻すかを判別する必要があります。
`deleted_at` の値の一致で判定すると、**たまたま同時刻に個別削除された行まで巻き込みます。**

`deletion_batch_id`（uuid, NULL 可）を持たせ、1 回の削除操作で消した行に同じ値を振ります。
復活はこの ID を単位に行います。個別に削除した行は独立した ID を持つため、巻き込みが起きません。

削除は**下へ**連鎖します。上へは連鎖しません。難易度を消すとクイズも消えるのは、
**難易度を失ったクイズが出題も編集もできなくなる**ためです。

生存している行がバッチを持っていたら、復活時の消し忘れにあたります。`CHECK` で弾きます。

```sql
ALTER TABLE quiz.categories ADD CONSTRAINT categories_batch_only_when_deleted
  CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL);

-- 削除済みを引くのは管理画面の「削除済み一覧」だけなので、部分インデックスにする
CREATE INDEX categories_deleted_idx
  ON quiz.categories (tenant_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
```

**実装済み（DEV-35）。** 削除 → 一部だけ個別に復活 → 親を復活、という順序で破綻しないこと、
親より前に個別削除した行が親の復活で戻らないことをテストで固定しています。

テーブルごとに 1 文の `UPDATE` で消します。行ごとのループにはしていないため、件数が増えても SQL の本数は変わりません。

---

## 未決定事項

| 項目 | 決める時期 |
| --- | --- |
| 削除済みを一定期間後に物理削除するバッチを設けるか | 運用開始後 |
| バッチ処理の実行基盤（ECS Scheduled Task / Lambda / アプリ内スケジューラ） | 設計時 |

### 採用しなかったもの

**`answers` に出題時のスナップショットを持たせる案**は採用しません。

クイズを修正した後も過去の履歴が当時の問題文を保つ、という利点があります。
しかし本アプリでは、**正解が変わるような修正は新しいクイズとして作る運用**で足ります。
誤字の修正で履歴の意味が変わることはありません。

データ量が回答数に比例して増え、実装も複雑になる割に、得られるものが見合いません。
