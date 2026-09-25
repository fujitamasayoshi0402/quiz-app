-- API のスモークテスト（tests/api）が使うテナントと利用者。**アプリの仕様ではなくテストの足場**（DEV-36）。
--
-- スモークテストはデプロイのたびに流れ、カテゴリやクイズを作っては消す。消したものはゴミ箱に残る。
-- デモのテナントを汚さないよう、専用のテナントで動かす。
-- カテゴリやクイズはテストが自分で作るため、ここでは入れない。
--
-- 投入の方法と、何度流しても増えない理由は R__demo_data.sql と同じ。

INSERT INTO core.tenants (id, slug, name) VALUES
    ('a3f7db95-decb-5940-98fa-d6a16787b927', 'smoke', 'スモークテスト')
    ON CONFLICT (id) DO NOTHING;

-- 利用者はメールアドレスだけで登録しておく。同じアドレスの Cognito の利用者（Terraform の modules/auth が作る）が
-- 最初にログインしたときに、ここに結び付く（ADR-0016）。example.com は誰も受け取れないアドレスなので、ほかの人は確認できない
INSERT INTO core.users (id, external_id, email, display_name) VALUES
    ('0b520e9c-a1d8-53e6-90c7-26376e1f7927', NULL, 'smoke@example.com', 'スモークテスト')
    ON CONFLICT (id) DO NOTHING;

-- スタブ認証の時代に入れた行（external_id が smoke-admin）を、結び付けられる形に直す。結び付いたあとは触らない
UPDATE core.users SET external_id = NULL, email = 'smoke@example.com'
    WHERE id = '0b520e9c-a1d8-53e6-90c7-26376e1f7927' AND external_id = 'smoke-admin';

INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES
    ('a3f7db95-decb-5940-98fa-d6a16787b927', '0b520e9c-a1d8-53e6-90c7-26376e1f7927', 'admin')
    ON CONFLICT DO NOTHING;
