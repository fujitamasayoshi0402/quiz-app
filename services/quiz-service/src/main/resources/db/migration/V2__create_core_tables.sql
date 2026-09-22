-- テナント。管理者ごとの独立したクイズ空間（ADR-0006）
CREATE TABLE core.tenants (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       text        NOT NULL,
    name       text        NOT NULL,
    visibility text        NOT NULL DEFAULT 'private',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT tenants_slug_format  CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'),
    CONSTRAINT tenants_name_length  CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT tenants_visibility   CHECK (visibility IN ('private', 'public'))
);

-- 削除したテナントの slug を再利用できるよう、生存行のみを一意にする（ADR-0007）
CREATE UNIQUE INDEX tenants_slug_key ON core.tenants (slug) WHERE deleted_at IS NULL;

CREATE TRIGGER tenants_set_updated_at
    BEFORE UPDATE ON core.tenants
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- ユーザー。認証情報は持たず、Cognito の識別子だけを保持する（ADR-0005）
CREATE TABLE core.users (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id  text        NOT NULL UNIQUE,
    display_name text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 100)
);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON core.users
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- テナントへの所属。1 人が複数テナントに所属できる
CREATE TABLE core.tenant_members (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES core.tenants (id),
    user_id    uuid        NOT NULL REFERENCES core.users (id),
    role       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT tenant_members_role CHECK (role IN ('admin', 'member'))
);

CREATE UNIQUE INDEX tenant_members_unique
    ON core.tenant_members (tenant_id, user_id) WHERE deleted_at IS NULL;

-- 所属テナント一覧の取得に使う
CREATE INDEX tenant_members_user_idx
    ON core.tenant_members (user_id) WHERE deleted_at IS NULL;

CREATE TRIGGER tenant_members_set_updated_at
    BEFORE UPDATE ON core.tenant_members
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
