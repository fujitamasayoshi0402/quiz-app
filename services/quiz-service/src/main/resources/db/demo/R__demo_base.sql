-- デモのテナントの土台（DEV-191）。dev と prod の両方に入れる。**アプリの仕様ではなくサンプル**。
--
-- 入れるのは、デモのテナント、見に来た人が試すための共有のデモのアカウント、デモのテナントの管理者（運用者）だけ。
-- クイズは入れない。デモのパック（content/demo/）を、管理者が API で流し込む（ADR-0034）。
-- dev だけのもの（2 つ目のテナント、所属の数を試す利用者、AI の受け箱）は db/seed/R__demo_data.sql にある。
-- Flyway の repeatable は説明の順に流れ、この土台（demo base）は dev のシード（demo data）より先に流れる。
--
-- 投入の方法と、何度流しても増えない理由は R__demo_data.sql と同じ。

INSERT INTO core.tenants (id, slug, name) VALUES
    ('7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'demo', 'デモ')
    ON CONFLICT (id) DO NOTHING;

-- 見に来た人が試すための、共有のデモのアカウント（DEV-104）。デモのテナントの一般ユーザーで、クイズは変えられない。
-- メールアドレスだけで登録しておき、同じアドレスの Cognito の利用者（Terraform の modules/auth が作る）が
-- 最初にログインしたときに結び付く（R__smoke_data.sql と同じ）
INSERT INTO core.users (id, external_id, email, display_name) VALUES
    ('0b66aaf5-727d-5da9-bf87-6756dff5b046', NULL, 'demo@example.com', 'デモの見学者')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES
    ('7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0b66aaf5-727d-5da9-bf87-6756dff5b046', 'member')
    ON CONFLICT DO NOTHING;

-- パスワードを公開している共有のアカウントなので、テナントは作れない（ADR-0028）。
-- 作れると、最初の 1 人が数の上限を使い切り、ほかの人が書いたものを全員が見る
UPDATE core.users SET shared = true WHERE id = '0b66aaf5-727d-5da9-bf87-6756dff5b046' AND NOT shared;

-- デモのテナントの管理者（運用者）。デモのパックを流し、AI の下書きを確かめる（開発ガイドライン「最初の管理者」）。
-- メールアドレスだけで登録しておき、同じアドレスで Cognito にサインアップして最初にログインしたときに結び付く。
-- すでに同じアドレスの利用者がいれば（dev）、その利用者を管理者にする。アドレスは一意（users_email_key）
INSERT INTO core.users (id, external_id, email, display_name) VALUES
    ('d3c1f1a2-6b0e-5f4c-9a7d-2e8b5c4f1a90', NULL, 'max3max7.0402@gmail.com', '運用者')
    ON CONFLICT DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role)
SELECT '7fd43527-dbbf-525e-9f33-f48e4e507fd1', u.id, 'admin' FROM core.users u
WHERE lower(u.email) = 'max3max7.0402@gmail.com'
ON CONFLICT DO NOTHING;
