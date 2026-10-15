-- AI が公式の技術ニュースから作ったクイズに、元にした発表の URL を持たせる（ADR-0031、DEV-161）。
--
-- 人が作ったクイズは空のまま。空でないことが「AI が作ったもの」の印になる。公開したあとも残し、解く人に出典を示す。
-- 列を足すだけなので、1 つ前のアプリはこの列を知らずに読み書きできる（更新しても値は変わらない）
ALTER TABLE quiz.quizzes
    ADD COLUMN source_url text
        CONSTRAINT quizzes_source_url_check CHECK (source_url ~ '^https://' AND char_length(source_url) <= 2048);
