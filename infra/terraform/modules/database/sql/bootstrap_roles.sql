-- マイグレーション用とアプリ用のロールを作る。Terraform（main.tf の bootstrap_roles）が Data API で流す。
--
--   quiz     … スキーマの所有者。マイグレーション（DDL）を実行する
--   quiz_app … アプリケーションの接続先。所有者でもスーパーユーザーでもないため、RLS が効く
--
-- どちらもパスワードを持たず、IAM 認証（rds_iam）で接続する（ADR-0014）。
-- 表や権限はマイグレーション（V6__grant_app_role.sql など）が作る。ここではロールと、スキーマを作る権限だけを用意する。
-- ローカルで同じ役割を持つのは infra/docker/postgres/init/01-create-app-role.sql。
--
-- Data API の 1 回の呼び出しで流すのは 1 文にする。複数の文の扱いが呼び出し方によって異なるため、DO ブロックにまとめる。
-- 何度流しても同じ結果になるように書く。
DO $$
BEGIN
    -- 属性は既定（NOSUPERUSER / NOBYPASSRLS / NOCREATEDB / NOCREATEROLE）のままにする。
    -- BYPASSRLS を持つと、所有者でなくても RLS を素通りする
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'quiz') THEN
        CREATE ROLE quiz LOGIN;
    END IF;

    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'quiz_app') THEN
        CREATE ROLE quiz_app LOGIN;
    END IF;

    GRANT rds_iam TO quiz, quiz_app;

    -- マイグレーションがスキーマ（core / quiz / answer）を作る。spring.flyway.create-schemas
    GRANT CREATE ON DATABASE quiz TO quiz;
END
$$;
