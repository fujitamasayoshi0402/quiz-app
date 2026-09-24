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

INSERT INTO core.users (id, external_id, display_name) VALUES
    ('0b520e9c-a1d8-53e6-90c7-26376e1f7927', 'smoke-admin', 'スモークテスト')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES
    ('a3f7db95-decb-5940-98fa-d6a16787b927', '0b520e9c-a1d8-53e6-90c7-26376e1f7927', 'admin')
    ON CONFLICT DO NOTHING;
