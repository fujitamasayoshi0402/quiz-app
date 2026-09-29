-- 解説図の種類（ADR-0020）。draw.io の図に、画像（PNG・JPEG）を加える。
-- 種類によって、本体を置く場所と配る形式が違う。今ある行は draw.io の図
--
-- 既定値を付けるのは、1 つ前のアプリ（種類を知らない）が、デプロイの間も図を置けるようにするため
ALTER TABLE quiz.figures
    ADD COLUMN kind text NOT NULL DEFAULT 'drawio'
        CONSTRAINT figures_kind_check CHECK (kind IN ('drawio', 'image'));
