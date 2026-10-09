-- 挑戦と回答をテナントで分離する（ADR-0006）。
--
-- これまで answer スキーマは tenant_id を持たず、アプリケーション層は user_id しか見ていなかった。
-- 複数のテナントに所属する利用者では、テナント A の URL から B の挑戦を参照・終了できた。
-- クイズ側と同じく tenant_id と RLS で DB に分離を担保させる。
--
-- RLS が絞るのは「アクセス先のテナント」であって所属ではない。
-- 将来の公開テナントで、所属していない利用者が回答することとは矛盾しない。

-- 既存の挑戦はどのテナントのものか、出題リストのクイズを辿らないと分からない。
-- クイズ側は RLS がかかっており、マイグレーションの実行ロールによっては辿れずに黙って欠ける。
-- Phase 1 は未リリースで、存在するのはローカルの動作確認分のみのため削除する。
-- attempt_quizzes と answers は ON DELETE CASCADE で一緒に消える
DELETE FROM answer.attempts;

ALTER TABLE answer.attempts
    ADD COLUMN tenant_id uuid NOT NULL REFERENCES core.tenants (id),
    ADD CONSTRAINT attempts_id_tenant_key UNIQUE (id, tenant_id);

-- 子の tenant_id は親と一致させる。複合外部キーにしないと、
-- 別テナントの挑戦に出題リストや回答をぶら下げられる
ALTER TABLE answer.attempt_quizzes
    ADD COLUMN tenant_id uuid NOT NULL,
    DROP CONSTRAINT attempt_quizzes_attempt_id_fkey,
    ADD CONSTRAINT attempt_quizzes_attempt_fk
        FOREIGN KEY (attempt_id, tenant_id) REFERENCES answer.attempts (id, tenant_id) ON DELETE CASCADE;

ALTER TABLE answer.answers
    ADD COLUMN tenant_id uuid NOT NULL,
    DROP CONSTRAINT answers_attempt_id_fkey,
    ADD CONSTRAINT answers_attempt_fk
        FOREIGN KEY (attempt_id, tenant_id) REFERENCES answer.attempts (id, tenant_id) ON DELETE CASCADE;

-- 中断中の挑戦は「テナントごとに」1 件まで。
-- 利用者単位のままだと、テナント A の挑戦が B で新しく始めることを妨げる
DROP INDEX answer.attempts_single_in_progress_idx;
CREATE UNIQUE INDEX attempts_single_in_progress_idx
    ON answer.attempts (tenant_id, user_id) WHERE status = 'in_progress';

ALTER TABLE answer.attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.attempts
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE answer.attempt_quizzes ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.attempt_quizzes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.attempt_quizzes
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE answer.answers ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.answers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.answers
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
