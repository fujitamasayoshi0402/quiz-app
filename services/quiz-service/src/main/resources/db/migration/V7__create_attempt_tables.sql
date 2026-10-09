-- 挑戦（1 回のクイズセッション）。
--
-- 回答だけを持つと「10 問中 7 問正解」を表せない。結果画面にもランキングにも、
-- どこからどこまでが 1 回かという区切りが要る。
--
-- answers と同じく tenant_id を持たない（ADR-0006）。
-- quiz スキーマへの外部キーも貼らない。answer モジュールを分離するときの障害になるため（ADR-0004）。
-- 参照先が消えうることを前提に、再開時に出題リストを検証する。
CREATE TABLE answer.attempts (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES core.users (id),
    category_id   uuid,
    difficulty_id uuid,
    level         integer,
    scope         text        NOT NULL,
    status        text        NOT NULL DEFAULT 'in_progress',
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    CONSTRAINT attempts_scope_check
        CHECK (scope IN ('all', 'unanswered', 'unanswered_only')),
    CONSTRAINT attempts_status_check
        CHECK (status IN ('in_progress', 'completed', 'abandoned')),
    -- 終了した挑戦は必ず終了時刻を持ち、回答中の挑戦は持たない
    CONSTRAINT attempts_finished_at_check
        CHECK ((status = 'in_progress') = (finished_at IS NULL))
);

-- 中断中の挑戦は 1 ユーザーにつき 1 件まで。
-- 複数持てると「どれを再開しますか」の一覧画面が要る（要件定義）。
-- 部分ユニークインデックスなら、完了・破棄した挑戦は何件でも残せる
CREATE UNIQUE INDEX attempts_single_in_progress_idx
    ON answer.attempts (user_id) WHERE status = 'in_progress';

CREATE INDEX attempts_user_time_idx ON answer.attempts (user_id, started_at DESC);

-- 出題されたクイズと、その順序。
-- これをサーバーに持つことで、別の端末からでも再開できる。
-- ブラウザに置くと、回答（サーバー）と出題順（ブラウザ）で状態が 2 か所に分かれる
CREATE TABLE answer.attempt_quizzes (
    attempt_id uuid    NOT NULL REFERENCES answer.attempts (id) ON DELETE CASCADE,
    quiz_id    uuid    NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (attempt_id, sort_order),
    -- 同じクイズを 1 回の挑戦で二度出さない
    CONSTRAINT attempt_quizzes_quiz_unique UNIQUE (attempt_id, quiz_id)
);

-- 既存の回答はどの挑戦に属していたか復元できない。
-- Phase 1 は未リリースで、存在するのはローカルの動作確認分のみのため削除する
DELETE FROM answer.answers;

ALTER TABLE answer.answers
    ADD COLUMN attempt_id uuid NOT NULL REFERENCES answer.attempts (id) ON DELETE CASCADE;

-- 1 回の挑戦で同じクイズに二度答えさせない。
-- 挑戦をまたいだ解き直しは許す（履歴を上書きしない）ので、user_id は含めない
CREATE UNIQUE INDEX answers_attempt_quiz_idx ON answer.answers (attempt_id, quiz_id);

-- RLS を設定しない理由は V5 の answer.answers と同じ。
-- tenant_id を持たず、ランキング（Phase 4）で他の利用者の記録を集計するため。
-- 本人以外の挑戦を操作させないことはアプリケーション層で担保する
