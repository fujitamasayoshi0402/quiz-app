-- テナント分離を DB 側でも守る（ADR-0006）。
-- アプリケーションが WHERE tenant_id = ? を書き漏らしても、他テナントの行が返らない状態にする。
--
-- 論理削除の条件（deleted_at IS NULL）はポリシーに含めない。
-- 含めると削除済み一覧を取得できず、復活機能が作れなくなるため（ADR-0007）。
-- 削除条件はリポジトリ層の共通機構で強制する。

-- セッション変数が未設定のときの挙動を一定にするため nullif を挟む。
-- 一度も SET していなければ NULL、RESET 後は空文字列が返るため、
-- 素直に ::uuid へキャストすると後者だけがエラーになる。
-- nullif で NULL に揃えることで、どちらの場合も「0 件」で一貫する。
-- 設定漏れを検知する仕組みはアプリケーション層に置く。

-- FORCE を付けないとテーブル所有者にはポリシーが適用されない。
-- アプリケーションが所有者で接続する構成のため、FORCE を明示する。
-- 本番でロールを分離する場合も、FORCE があって困ることはない。

ALTER TABLE quiz.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.categories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.categories
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE quiz.difficulties ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.difficulties FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.difficulties
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE quiz.quizzes ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.quizzes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.quizzes
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE quiz.choices ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.choices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.choices
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- RLS を設定しないテーブルと、その理由。
--
-- core.tenants / core.tenant_members:
--   テナント境界の「中」にあるデータではなく、境界そのものを定義するテーブル。
--   所属テナント一覧の取得は、どのテナントで作業するかが決まる「前」に行われるため、
--   app.tenant_id がまだ設定されていない。ここに RLS をかけると一覧を取得できず、
--   複数テナントに所属するユーザーが最初の画面から先へ進めなくなる。
--   参照できる範囲はアプリケーション層で user_id により制御する。
--
-- core.users:
--   テナントに属さない。所属は tenant_members が表す。
--
-- answer.answers:
--   tenant_id を持たない（ADR-0006）。加えてランキング（Phase 4）で他の利用者の回答を
--   集計するため、ポリシーで本人に限定すると機能が作れない。アプリケーション層で制御する。
