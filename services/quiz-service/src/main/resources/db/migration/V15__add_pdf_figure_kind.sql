-- 解説図の種類に PDF を加える（ADR-0020）。PDF は解説のリンクから開く資料で、本文の中には出さない
ALTER TABLE quiz.figures DROP CONSTRAINT figures_kind_check;
ALTER TABLE quiz.figures
    ADD CONSTRAINT figures_kind_check CHECK (kind IN ('drawio', 'image', 'pdf'));
