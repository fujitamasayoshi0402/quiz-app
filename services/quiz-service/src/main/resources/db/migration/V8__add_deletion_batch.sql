-- 連鎖復活のための削除バッチ。
--
-- 「カテゴリと一緒に削除されたもの」を判別する必要がある（ADR-0007）。
-- deleted_at の一致で判定する手もあるが、**同じ時刻になったのが偶然か意図かを区別できない**。
-- 削除操作に ID を振り、同じ操作で消えたことを列として持たせる。
--
-- 復活したら NULL に戻す。次に消したときは新しいバッチになる。
ALTER TABLE quiz.categories   ADD COLUMN deletion_batch_id uuid;
ALTER TABLE quiz.difficulties ADD COLUMN deletion_batch_id uuid;
ALTER TABLE quiz.quizzes      ADD COLUMN deletion_batch_id uuid;

-- 削除済み一覧と、バッチ単位の復活で引く
CREATE INDEX categories_deleted_idx
    ON quiz.categories (tenant_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
CREATE INDEX difficulties_deleted_idx
    ON quiz.difficulties (tenant_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
CREATE INDEX quizzes_deleted_idx
    ON quiz.quizzes (tenant_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;

-- 生存している行はバッチを持たない。持っていたら復活時の消し忘れ
ALTER TABLE quiz.categories ADD CONSTRAINT categories_batch_only_when_deleted
    CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL);
ALTER TABLE quiz.difficulties ADD CONSTRAINT difficulties_batch_only_when_deleted
    CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL);
ALTER TABLE quiz.quizzes ADD CONSTRAINT quizzes_batch_only_when_deleted
    CHECK (deletion_batch_id IS NULL OR deleted_at IS NOT NULL);

-- 連鎖削除が入る前に消されたカテゴリがあると、配下のクイズが生きたまま残る。
-- 開発中のデータのみなので、ここで辻褄を合わせる
UPDATE quiz.quizzes q SET deleted_at = c.deleted_at
    FROM quiz.categories c
    WHERE q.category_id = c.id AND c.deleted_at IS NOT NULL AND q.deleted_at IS NULL;
UPDATE quiz.difficulties d SET deleted_at = c.deleted_at
    FROM quiz.categories c
    WHERE d.category_id = c.id AND c.deleted_at IS NOT NULL AND d.deleted_at IS NULL;
