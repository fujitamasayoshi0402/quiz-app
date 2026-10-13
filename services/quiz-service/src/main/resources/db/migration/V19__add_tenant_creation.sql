-- ログインした人が、自分のテナントを作れるようにする（ADR-0028）。
--
-- created_by: 作った人。運用者がシードや運用の手順で作ったテナントは NULL。**NULL でないことが「利用者が作ったテナント」の印**。
-- quiz_limit / figure_limit: クイズと図の数の上限。NULL なら上限なし。利用者が作ったテナントにだけ、作るときに入れる。
-- 運用者は、この列を書き換えて上限を変える
ALTER TABLE core.tenants
    ADD COLUMN created_by   uuid    REFERENCES core.users (id),
    ADD COLUMN quiz_limit   integer,
    ADD COLUMN figure_limit integer,
    ADD CONSTRAINT tenants_limits_not_negative CHECK (quiz_limit >= 0 AND figure_limit >= 0);

-- 1 人 1 つ。同時に 2 つ作る要求が来ても、片方は一意制約で失敗する
CREATE UNIQUE INDEX tenants_created_by_key ON core.tenants (created_by) WHERE deleted_at IS NULL;

-- 共有のアカウント（パスワードを公開しているデモのアカウント）。テナントを作れない。シードが付ける
ALTER TABLE core.users ADD COLUMN shared boolean NOT NULL DEFAULT false;
