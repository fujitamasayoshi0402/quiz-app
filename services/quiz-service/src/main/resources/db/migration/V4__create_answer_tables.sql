-- 回答履歴。
-- tenant_id を持たない。将来テナントを公開したとき、所属していないユーザーが回答するため（ADR-0006）
-- quiz_id への外部キーも貼らない。answer-service を分離するときに障害になるため（ADR-0004）
CREATE TABLE answer.answers (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES core.users (id),
    quiz_id     uuid        NOT NULL,
    choice_id   uuid        NOT NULL,
    is_correct  boolean     NOT NULL,
    answered_at timestamptz NOT NULL DEFAULT now()
);

-- 未回答優先の出題で使う
CREATE INDEX answers_user_quiz_idx ON answer.answers (user_id, quiz_id);

-- 履歴とスコアの集計で使う
CREATE INDEX answers_user_time_idx ON answer.answers (user_id, answered_at DESC);
