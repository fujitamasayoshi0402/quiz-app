-- アプリケーション用ロールへの権限付与。
-- マイグレーションは所有者（quiz）が実行し、アプリケーションは quiz_app で接続する。
-- quiz_app はスーパーユーザーではないため RLS が適用される。

GRANT USAGE ON SCHEMA core, quiz, answer TO quiz_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core, quiz, answer TO quiz_app;

-- 今後のマイグレーションで追加されるテーブルにも自動で権限が付くようにする。
-- これを忘れると、テーブルを足すたびに GRANT の書き漏れで動かなくなる
ALTER DEFAULT PRIVILEGES IN SCHEMA core, quiz, answer
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quiz_app;

-- Flyway の履歴テーブルは参照させない（アプリケーションが触る必要がない）
REVOKE ALL ON core.flyway_schema_history FROM quiz_app;
