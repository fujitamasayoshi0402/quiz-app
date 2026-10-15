-- 「あとで見直す」印（DEV-159）。利用者が問題に付け、印の付いた問題だけを解き直せる。
--
-- 回答の履歴ではなく本人の設定なので、外したら行を消す。論理削除にしない（ADR-0007 の対象外。ranking_entries と同じ）。
-- quiz スキーマへの外部キーは貼らない（ADR-0004）。クイズが消えても印は残り、
-- 出題と一覧で、いま出題できるクイズに絞る
CREATE TABLE answer.bookmarks (
    tenant_id  uuid        NOT NULL REFERENCES core.tenants (id),
    user_id    uuid        NOT NULL REFERENCES core.users (id),
    quiz_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 利用者の印をまとめて引くので、主キーの並びを (tenant_id, user_id) から始める
    PRIMARY KEY (tenant_id, user_id, quiz_id)
);

ALTER TABLE answer.bookmarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.bookmarks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.bookmarks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 出題対象に「あとで見直す問題」（bookmarked）を足す。
-- 入れ替えの間に古いタスクがこの挑戦を読むと失敗するのは、V20 の incorrect と同じ。入れ替えは数分で終わるため受け入れる
ALTER TABLE answer.attempts
    DROP CONSTRAINT attempts_scope_check,
    ADD CONSTRAINT attempts_scope_check
        CHECK (scope IN ('all', 'unanswered', 'unanswered_only', 'incorrect', 'bookmarked'));
