-- ランキングへの参加（DEV-73）。**参加を選んだ人だけが載る。**
--
-- 名前はテナントごとに本人が決める。core.users.display_name は使わない。
-- 表示名はメールアドレスの @ より前から作っており、そのまま出すとアドレスの一部が同じテナントの人に見える。
-- テナントごとに持つのは、所属する集まりによって名乗り方を変えられるようにするため。
--
-- 参加をやめたら行を消す。回答の履歴ではなく本人の設定なので、論理削除にしない（ADR-0007 の対象外）。
-- 所属（core.tenant_members）への外部キーは貼らない。所属は論理削除のため、外部キーでは「外れた」を表せない。
-- 所属から外れた人を載せないことは、ランキングを返すときに確かめる
CREATE TABLE answer.ranking_entries (
    tenant_id  uuid        NOT NULL REFERENCES core.tenants (id),
    user_id    uuid        NOT NULL REFERENCES core.users (id),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ranking_entries_name_length CHECK (char_length(name) BETWEEN 1 AND 20)
);

-- 同じテナントで同じ名前を使わせない。並んだときに誰なのか区別できなくなる
CREATE UNIQUE INDEX ranking_entries_name_key ON answer.ranking_entries (tenant_id, lower(name));

CREATE TRIGGER ranking_entries_set_updated_at
    BEFORE UPDATE ON answer.ranking_entries
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

ALTER TABLE answer.ranking_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.ranking_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.ranking_entries
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
