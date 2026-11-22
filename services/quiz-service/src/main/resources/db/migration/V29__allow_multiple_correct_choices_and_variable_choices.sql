-- 選択肢の数を 2〜6 個から選べるようにし、複数の正解（すべて選べ）に対応する（DEV-243）。
--
-- 1 クイズにつき正解を 1 つまでに限っていた一意索引を外す。
-- 「公開するには選択肢 2〜6 個・正解 1 個以上」は行数の決まりのため、これまでどおりドメイン層と整合性チェックで守る。
DROP INDEX quiz.choices_single_correct;

-- 1 回の回答で選んだ選択肢。単一選択では 1 行、複数選択では選んだ数だけ行を持つ。
-- answer.answers の 1 行が「1 問への 1 回の回答」を表すことは変わらない（履歴・正答率・ランキングの集計がそのまま動く）。
-- answer.answers.choice_id は最初の 1 つを入れて残し、選んだ選択肢の一覧はここから読む。
CREATE TABLE answer.answer_choices (
    tenant_id   uuid    NOT NULL,
    answer_id   uuid    NOT NULL,
    choice_id   uuid    NOT NULL,
    sort_order  integer NOT NULL,
    PRIMARY KEY (answer_id, sort_order),
    CONSTRAINT answer_choices_choice_unique UNIQUE (answer_id, choice_id)
);

ALTER TABLE answer.answers
    ADD CONSTRAINT answers_id_tenant_key UNIQUE (id, tenant_id);

ALTER TABLE answer.answer_choices
    ADD CONSTRAINT answer_choices_answer_fk
        FOREIGN KEY (answer_id, tenant_id) REFERENCES answer.answers (id, tenant_id) ON DELETE CASCADE;

CREATE INDEX answer_choices_choice_idx ON answer.answer_choices (choice_id);

-- これまでの回答を写す
INSERT INTO answer.answer_choices (tenant_id, answer_id, choice_id, sort_order)
SELECT tenant_id, id, choice_id, 0 FROM answer.answers;

ALTER TABLE answer.answer_choices ENABLE ROW LEVEL SECURITY;
ALTER TABLE answer.answer_choices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON answer.answer_choices
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
