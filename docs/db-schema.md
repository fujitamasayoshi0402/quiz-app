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

---

## スキーマ分割

サービス境界をスキーマで表現します（[ADR-0004](adr/0004-split-services-incrementally.md)）。

| スキーマ | テーブル | 将来の分離先 |
| --- | --- | --- |
| `core` | `tenants` / `users` / `tenant_members` | 分離しない（共通） |
| `quiz` | `categories` / `difficulties` / `quizzes` / `choices` | quiz-service |
| `answer` | `answers` | answer-service |

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
| `created_at` / `updated_at` | timestamptz | NOT NULL |
| `deleted_at` | timestamptz | NULL 可 |

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

CREATE INDEX quizzes_filter_idx
  ON quiz.quizzes (tenant_id, category_id, difficulty_id) WHERE deleted_at IS NULL;
```

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

`tenant_id` を持ちません。将来テナントを公開したとき、所属していないユーザーが回答するためです
（[ADR-0006](adr/0006-row-level-multi-tenancy.md)）。

論理削除もしません。履歴そのものであり、削除する操作を設けないためです。

---

## RLS ポリシー

`quiz` スキーマの全テーブルと `core.tenant_members` に適用します。

```sql
ALTER TABLE quiz.categories ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.categories
  USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

アプリケーションは接続ごとに `SET LOCAL app.tenant_id = '...'` を実行します。
**HikariCP は接続を使い回すため、返却時にリセットしないと前のリクエストのテナントが残ります**（DEV-34 で検証）。

### 論理削除の条件は含めない

ポリシーは `tenant_id` のみを見ます。`deleted_at IS NULL` を含めると、
**削除済み一覧を取得できなくなり、復活機能が作れません。**

削除済みが表示される事故は「表示の不整合」であり、テナント境界を越える情報漏洩とは重さが違います。
削除条件はリポジトリ層の共通機構で強制し、削除済みを取得する操作は復活機能専用のメソッドに限定します
（[ADR-0007](adr/0007-soft-delete-master-data.md)）。

### answers に RLS を設定しない

`answers` は `tenant_id` を持たないため、テナントによる分離ができません。
`user_id` で絞る案もありますが、ランキング（Phase 4）では他の利用者の回答を集計するため、
ポリシーで本人に限定すると機能が作れなくなります。

回答の参照はアプリケーション層で制御します。
個別の回答履歴は本人のみ、集計は匿名化した結果のみ、という区別をユースケースごとに実装します。

**これは RLS による保護が効かない唯一のテーブルです。** 回答を扱う実装では、
`user_id` の条件を明示的に書く必要があります。

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

> 削除 → 一部だけ個別に復活 → 親を復活、という順序でも破綻しないことを確認する必要があります（DEV-35）。

---

## 未決定事項

| 項目 | 決める時期 |
| --- | --- |
| 下書き / 公開の状態をクイズに持たせるか | Phase 4（管理機能の作り込み） |
| `updated_at` の更新をトリガーで行うか、アプリケーションで行うか | DEV-18 |
| 削除済みを一定期間後に物理削除するバッチを設けるか | 運用開始後 |
| `answers` に出題時のスナップショットを持たせるか（クイズ修正後の履歴の見え方） | Phase 4 |
