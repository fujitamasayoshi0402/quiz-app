-- 管理画面のクイズの一覧で、クイズごとに回答を数える（DEV-158）。
-- これまでの索引は利用者が先頭にあり、利用者をまたいでクイズで引くと、テナントの回答をすべて読む
CREATE INDEX answers_quiz_idx ON answer.answers (quiz_id);
