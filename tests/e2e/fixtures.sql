-- E2E テスト（tests/e2e）のテナントと所属。テストの前に global-setup.ts が毎回流す。何度流しても同じ状態になる。
--
-- 利用者はメールアドレスだけで登録する。Terraform が作った Cognito の利用者が最初にログインしたときに、
-- 同じアドレスのこの行に結び付く（事前の登録。開発ガイドラインの「最初の管理者」と同じ仕組み）。
-- 未所属の人と招待される人は、所属を持たない状態に戻す。前の実行で招待を受け入れていても、もう一度招待できる

INSERT INTO core.tenants (slug, name) VALUES ('e2e', 'E2E テスト') ON CONFLICT DO NOTHING;

INSERT INTO core.users (email, display_name)
VALUES ('e2e-admin@example.com', 'E2E 管理者'),
       ('e2e-member@example.com', 'E2E 一般ユーザー'),
       ('e2e-outsider@example.com', 'E2E 未所属'),
       ('e2e-invitee@example.com', 'E2E 招待される人')
ON CONFLICT DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role)
SELECT t.id, u.id, v.role
FROM (VALUES ('e2e-admin@example.com', 'admin'), ('e2e-member@example.com', 'member')) AS v (email, role)
JOIN core.users u ON lower(u.email) = v.email
JOIN core.tenants t ON t.slug = 'e2e' AND t.deleted_at IS NULL
ON CONFLICT (tenant_id, user_id) WHERE deleted_at IS NULL DO NOTHING;

UPDATE core.tenant_members m SET deleted_at = now()
FROM core.users u, core.tenants t
WHERE m.user_id = u.id AND m.tenant_id = t.id AND t.slug = 'e2e' AND m.deleted_at IS NULL
  AND lower(u.email) IN ('e2e-outsider@example.com', 'e2e-invitee@example.com');

-- 前の実行が途中で止まって残った招待を取り消す。招待の一覧が空の状態から始める
UPDATE core.invitations i SET revoked_at = now()
FROM core.tenants t
WHERE i.tenant_id = t.id AND t.slug = 'e2e' AND i.accepted_at IS NULL AND i.revoked_at IS NULL;
