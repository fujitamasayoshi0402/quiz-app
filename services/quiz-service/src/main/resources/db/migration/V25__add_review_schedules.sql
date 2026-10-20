-- 間隔反復（DEV-178、ADR-0033）。利用者 × 問題ごとに、次に出す日を持つ。
--
-- 回答の履歴から毎回計算せず、回答を受けたときに更新して持つ。次に出す日は、それまでの正誤の並び全体で決まり、
-- 出題のたびに履歴をすべて読み直すことになるため。
-- 回答の履歴と同じく、利用者がクイズに残した記録として answer が持つ。quiz スキーマへの外部キーは貼らない（ADR-0004）
CREATE TABLE answer.review_schedules (
    tenant_id     uuid             NOT NULL REFERENCES core.tenants (id),
    user_id       uuid             NOT NULL REFERENCES core.users (id),
    quiz_id       uuid             NOT NULL,
    -- 続けて正解した回数。間違えると 0 に戻る
    repetitions   integer          NOT NULL CHECK (repetitions >= 0),
    interval_days integer          NOT NULL CHECK (interval_days >= 0),
    -- 覚えやすさの係数。正解したときに間隔へ掛ける
    ease          double precision NOT NULL CHECK (ease >= 1.3),
    -- 次に出す日。日本時間の日付
    due_on        date             NOT NULL,
    updated_at    timestamptz      NOT NULL DEFAULT now(),
    -- 利用者の状態をまとめて引くので、主キーの並びを (tenant_id, user_id) から始める
    PRIMARY KEY (tenant_id, user_id, quiz_id)
);

ALTER TABLE answer.review_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.review_schedules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.review_schedules
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 出題対象に「今日の復習」（review）を足す。
-- 入れ替えの間に古いタスクがこの挑戦を読むと失敗するのは、V20 の incorrect と同じ。入れ替えは数分で終わるため受け入れる
ALTER TABLE answer.attempts
    DROP CONSTRAINT attempts_scope_check,
    ADD CONSTRAINT attempts_scope_check
        CHECK (scope IN ('all', 'unanswered', 'unanswered_only', 'incorrect', 'bookmarked', 'review'));
