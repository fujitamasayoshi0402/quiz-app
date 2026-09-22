-- 更新日時を自動で設定するトリガー関数。
-- アプリケーション層ではなく DB 側に置く理由は docs/db-schema.md を参照。
-- バッチが SQL を直接実行しても updated_at が漏れなく更新される。
CREATE OR REPLACE FUNCTION core.set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
