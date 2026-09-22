-- クイズの分類。階層は持たない（docs/domain-model.md）
CREATE TABLE quiz.categories (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES core.tenants (id),
    name        text        NOT NULL,
    description text,
    sort_order  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT categories_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    -- 複合外部キーの参照先として必要
    CONSTRAINT categories_id_tenant_key UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX categories_name_key
    ON quiz.categories (tenant_id, name) WHERE deleted_at IS NULL;

CREATE INDEX categories_list_idx
    ON quiz.categories (tenant_id, sort_order) WHERE deleted_at IS NULL;

CREATE TRIGGER categories_set_updated_at
    BEFORE UPDATE ON quiz.categories
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- 難易度。カテゴリごとに定義する。分野によって適切な尺度が異なるため
CREATE TABLE quiz.difficulties (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    category_id uuid        NOT NULL,
    name        text        NOT NULL,
    level       integer     NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT difficulties_name_length CHECK (char_length(name) BETWEEN 1 AND 50),
    CONSTRAINT difficulties_level_positive CHECK (level >= 1),
    -- カテゴリが同じテナントに属することを DB が保証する
    CONSTRAINT difficulties_category_fk
        FOREIGN KEY (category_id, tenant_id) REFERENCES quiz.categories (id, tenant_id),
    -- 複合外部キーの参照先として必要
    CONSTRAINT difficulties_id_category_key UNIQUE (id, category_id)
);

CREATE UNIQUE INDEX difficulties_name_key
    ON quiz.difficulties (category_id, name) WHERE deleted_at IS NULL;

-- level にユニーク制約は設けない。
-- AWS のアソシエイト級（SAA / DVA / SOA）のように、同じ難度帯に複数の種類が並ぶため
CREATE INDEX difficulties_level_idx
    ON quiz.difficulties (tenant_id, level) WHERE deleted_at IS NULL;

CREATE TRIGGER difficulties_set_updated_at
    BEFORE UPDATE ON quiz.difficulties
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- クイズ本体
CREATE TABLE quiz.quizzes (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL,
    category_id           uuid        NOT NULL,
    difficulty_id         uuid        NOT NULL,
    question              text        NOT NULL,
    explanation           text        NOT NULL,
    explanation_image_key text,
    status                text        NOT NULL DEFAULT 'draft',
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    CONSTRAINT quizzes_question_length CHECK (char_length(question) BETWEEN 1 AND 2000),
    CONSTRAINT quizzes_status CHECK (status IN ('draft', 'published')),
    -- カテゴリが同じテナントに属することを保証
    CONSTRAINT quizzes_category_fk
        FOREIGN KEY (category_id, tenant_id) REFERENCES quiz.categories (id, tenant_id),
    -- 難易度がそのカテゴリに属することを保証。
    -- これにより「カテゴリ: 認証認可 / 難易度: SAA」のような矛盾を DB が拒否する
    CONSTRAINT quizzes_difficulty_fk
        FOREIGN KEY (difficulty_id, category_id) REFERENCES quiz.difficulties (id, category_id),
    CONSTRAINT quizzes_id_tenant_key UNIQUE (id, tenant_id)
);

-- 出題は公開済みのみを対象にする
CREATE INDEX quizzes_filter_idx
    ON quiz.quizzes (tenant_id, category_id, difficulty_id)
    WHERE deleted_at IS NULL AND status = 'published';

-- 管理画面は下書きも含めて一覧する
CREATE INDEX quizzes_admin_idx
    ON quiz.quizzes (tenant_id, category_id) WHERE deleted_at IS NULL;

CREATE TRIGGER quizzes_set_updated_at
    BEFORE UPDATE ON quiz.quizzes
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- 選択肢。クイズに完全に従属するため論理削除しない
CREATE TABLE quiz.choices (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL,
    quiz_id    uuid        NOT NULL,
    body       text        NOT NULL,
    is_correct boolean     NOT NULL DEFAULT false,
    sort_order integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT choices_body_length CHECK (char_length(body) BETWEEN 1 AND 500),
    CONSTRAINT choices_quiz_fk
        FOREIGN KEY (quiz_id, tenant_id) REFERENCES quiz.quizzes (id, tenant_id) ON DELETE CASCADE
);

-- 1 クイズにつき正解は 1 つまで。
-- 「ちょうど 4 つ」「正解が 0 個でない」は行数の制約のため DB では表現できず、ドメイン層で守る
CREATE UNIQUE INDEX choices_single_correct ON quiz.choices (quiz_id) WHERE is_correct;

CREATE UNIQUE INDEX choices_order_key ON quiz.choices (quiz_id, sort_order);

CREATE TRIGGER choices_set_updated_at
    BEFORE UPDATE ON quiz.choices
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
