-- テナントへの招待（ADR-0016）。管理者が作り、招待された人がリンクから受け入れる。
--
-- 行レベルセキュリティは設定しない。tenant_members と同じく、テナント境界を定義する側のテーブル。
-- 受け入れるときは、どのテナントの招待かをトークンから引く。テナントが決まる前に読む必要がある。
-- 参照できる範囲はアプリケーション層で、テナント（管理者の操作）とトークン（受け入れ）により絞る。
--
-- 1 つ前のアプリはこのテーブルを知らずに動く。追加だけなので、そのまま両立する

CREATE TABLE core.invitations (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES core.tenants (id),
    email       text        NOT NULL,
    role        text        NOT NULL,
    -- リンクに入れるトークンの SHA-256（16 進）。トークンそのものは持たない。DB が漏れても、リンクは作れない
    token_hash  text        NOT NULL,
    invited_by  uuid        NOT NULL REFERENCES core.users (id),
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    accepted_by uuid        REFERENCES core.users (id),
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT invitations_role         CHECK (role IN ('admin', 'member')),
    CONSTRAINT invitations_email_length CHECK (char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT invitations_accepted     CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    -- 受け入れた招待は取り消せず、取り消した招待は受け入れられない
    CONSTRAINT invitations_closed_once  CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX invitations_token_hash_key ON core.invitations (token_hash);

-- 同じアドレスへの未使用の招待は、テナントごとに 1 つ。作り直すと、古いものを取り消す。
-- 招待の一覧（未使用のもの）の取得にも使う
CREATE UNIQUE INDEX invitations_open_email_key
    ON core.invitations (tenant_id, lower(email)) WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TRIGGER invitations_set_updated_at
    BEFORE UPDATE ON core.invitations
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
