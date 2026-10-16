-- デモ用のシードデータ。**アプリの仕様ではなくサンプル**（DEV-31）。
--
-- 投入方法は Flyway の repeatable マイグレーション。
-- 開発プロファイルでのみ locations に classpath:db/seed が加わるため、本番では読み込まれない
-- （二重の防御として com.quizapp.startup.SeedDataGuard が起動時に確かめる）。
--
-- 何度流しても増えないよう、識別子を固定して ON CONFLICT DO NOTHING で入れる。
-- repeatable マイグレーションは内容を変えると再実行されるため、この性質が要る。
--
-- デモのクイズは、ここではなくパック（content/demo/）に書き、API で流し込む（ADR-0034）。

-- テナントと利用者 ------------------------------------------------------------
-- 2 つ目のテナントは、テナントの選択と、テナントごとにロールが違うことを見せるためにある
INSERT INTO core.tenants (id, slug, name) VALUES
    ('7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'demo', 'デモ'),
    ('9efd94a0-e517-573b-b46e-0f1ae96fc342', 'geo-club', '地理の勉強会')
    ON CONFLICT (id) DO NOTHING;

-- この利用者のままではログインできない（external_id は Cognito の利用者ではない）。
-- 使うときは、自分のメールアドレスを割り当てて結び付ける（開発ガイドライン「最初の管理者」）
INSERT INTO core.users (id, external_id, display_name) VALUES
    ('67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab',  'demo-admin',  'デモ管理者'),
    ('957d085e-3b87-5fa7-9283-5eb6229216b1', 'demo-member', 'デモ利用者'),
    ('7918a5c2-30ee-56c8-b76c-57c6a79774e3', 'demo-outsider', 'デモ未所属')
    ON CONFLICT (id) DO NOTHING;

-- ロールはテナントごとに決まる。デモ管理者は、地理の勉強会では一般ユーザー。
-- 所属の数で `/` の挙動が変わる。デモ管理者は 2 つ（選択画面）、デモ利用者は 1 つ（自動で遷移）、
-- デモ未所属は 0（招待を受けていない旨を表示）
INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES
    ('7fd43527-dbbf-525e-9f33-f48e4e507fd1', '67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab',  'admin'),
    ('7fd43527-dbbf-525e-9f33-f48e4e507fd1', '957d085e-3b87-5fa7-9283-5eb6229216b1', 'member'),
    ('9efd94a0-e517-573b-b46e-0f1ae96fc342', '67d6db5a-9721-5d2e-b6ca-c39b2a9ba1ab',  'member')
    ON CONFLICT DO NOTHING;

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

-- テナント配下の行は、テナントを決めてから入れる --------------------------------
-- quiz 配下のテーブルは FORCE ROW LEVEL SECURITY で、所有者（マイグレーションを流す quiz）にもポリシーが効く。
-- アプリと同じく app.tenant_id を設定しないと、行を入れられない（TenantSession）。
-- 第 3 引数の true で、このマイグレーションのトランザクションの中だけに効く。
--
-- ローカルでは quiz がスーパーユーザーのため RLS を素通りし、設定しなくても入ってしまう。
-- Aurora の quiz はスーパーユーザーではないので、設定しないと拒否される（ADR-0014）。
SELECT set_config('app.tenant_id', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', true);

-- 見本に絞ったときに外したクイズ（DEV-164） -------------------------------------
-- デモのテナントは、見に来た人向けの見本に絞った。
-- 新しく作った DB には、もう入らない。すでに入っている DB（dev）では、ゴミ箱へ移す（論理削除。ADR-0007）。
-- 1 回の削除の操作として扱い、ゴミ箱からまとめて戻せるよう、削除のバッチを固定の ID にする
UPDATE quiz.quizzes SET deleted_at = now(), deletion_batch_id = 'a7bc86a8-2965-532e-af48-036fbe9034ce'
    WHERE tenant_id = '7fd43527-dbbf-525e-9f33-f48e4e507fd1' AND deleted_at IS NULL AND id IN (
    '0e464cf8-3fd8-5765-93c8-556f091192fa',
    '152f64f7-3928-5140-82e4-6d6f239e6b9c',
    '178e13cc-bd66-57f6-aebb-593173b696cd',
    '1ade7a9c-b95b-5d81-abee-6741456b881f',
    '2fb03e61-fe45-5799-a060-702627ed4e87',
    '32999a7c-760e-5f8e-8db1-9d6294f21acf',
    '38ab34aa-6a0d-5a41-85b0-3d0bec406edf',
    '3a850d7d-1629-5895-a2c0-040ef1444942',
    '3e677f54-bfb8-5d3c-90fc-226d25fb12b8',
    '432bd6b1-14cf-5abe-a078-870696d4597c',
    '56ed05d2-9b7f-516c-95ea-08bce7f0de1c',
    '5c545b38-5317-5827-8b52-266ae18efbd9',
    '7088e4f4-188d-5e29-b6c6-fe029837ce52',
    '72cb296b-10ba-53ca-b484-fdbc2a40f26e',
    '72fd145f-2cf2-58bf-8d86-088addde815a',
    '7fb10f8d-a9eb-5d7c-acc0-93b78814da45',
    '849bc682-c99b-5635-bcd7-da537a2bd21d',
    '86c57514-06d0-5f72-b21b-769dd758c2e1',
    '8d116cdc-93e1-5df6-aa99-a99687ba4be5',
    '953e098f-b3c0-5824-8d22-20dfe1d63d31',
    '956cfc92-a5f8-5438-b671-3df861a3a59e',
    '986465ac-4782-5272-a51d-5eb2f5390f5b',
    '9b752272-8acd-5d2b-b77e-505458cb25a9',
    'a496ddb0-bc2d-5308-8269-6a29809b7339',
    'b164f77c-47f9-5bbd-9125-075e4f658d48',
    'b547fa30-d927-5e8e-a33f-8f3650a61a6a',
    'b5d11209-0628-597e-b546-28e37fcb58be',
    'c7433d51-fd29-557c-92c3-5470eb1a88d8',
    'cd205f69-443a-5559-9060-5db2800f032d',
    'd09a83d5-be37-50d5-b86d-c7da570820a0',
    'dc00e34a-9dad-5d88-a88a-abc9f60e67b6',
    'df4dc426-09de-5db2-b5a7-3ad9dd0e45dd',
    'e18baa26-c06e-516a-baca-563a58a0d667',
    'e2492296-f42b-5de8-a239-7c5afa64370d',
    'e9d199ac-f69c-57da-841a-aadbdd7ced12',
    'f386f96e-a0e6-59b2-bc8a-d8b40bc2af26'
    );

-- 技術のカテゴリを外したもの（DEV-188） -----------------------------------------
-- デモのクイズは、パック（content/demo/）から API で流し込む（ADR-0034）。シードはテナントと利用者と、AI の受け箱だけを入れる。
-- 新しく作った DB には、もう入らない。すでに入っている DB（dev）では、アプリでカテゴリを消したときと同じく、
-- 配下の難易度とクイズごとゴミ箱へ移す（論理削除。ADR-0007）。4 つのカテゴリを 1 回の削除の操作として、削除のバッチを固定の ID にする
UPDATE quiz.quizzes SET deleted_at = now(), deletion_batch_id = '3d2b7f0e-6a1c-5e94-8b3f-0c7d9a1e2f48'
    WHERE tenant_id = '7fd43527-dbbf-525e-9f33-f48e4e507fd1' AND deleted_at IS NULL AND category_id IN (
    '63b3c609-bfa5-5db4-bc9a-ec709936969d',
    '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8',
    '0ce3f934-9f5b-5803-ac80-fb15da5aea62',
    '7a8fda3d-1b93-516e-9b78-00e8dd5778aa'
    );

UPDATE quiz.difficulties SET deleted_at = now(), deletion_batch_id = '3d2b7f0e-6a1c-5e94-8b3f-0c7d9a1e2f48'
    WHERE tenant_id = '7fd43527-dbbf-525e-9f33-f48e4e507fd1' AND deleted_at IS NULL AND category_id IN (
    '63b3c609-bfa5-5db4-bc9a-ec709936969d',
    '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8',
    '0ce3f934-9f5b-5803-ac80-fb15da5aea62',
    '7a8fda3d-1b93-516e-9b78-00e8dd5778aa'
    );

UPDATE quiz.categories SET deleted_at = now(), deletion_batch_id = '3d2b7f0e-6a1c-5e94-8b3f-0c7d9a1e2f48'
    WHERE tenant_id = '7fd43527-dbbf-525e-9f33-f48e4e507fd1' AND deleted_at IS NULL AND id IN (
    '63b3c609-bfa5-5db4-bc9a-ec709936969d',
    '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8',
    '0ce3f934-9f5b-5803-ac80-fb15da5aea62',
    '7a8fda3d-1b93-516e-9b78-00e8dd5778aa'
    );

-- 技術ニュースからの下書き（ADR-0031、DEV-161） ----------------------------------------
-- AI が AWS の What's New から作ったクイズの下書きを、quiz-service がここに入れる（app.news-quizzes）。
-- シードではクイズを入れない。公開したものがないうちは、出題の画面に出ない
INSERT INTO quiz.categories (id, tenant_id, name, description, sort_order) VALUES
    ('2f6c1b8e-4d0a-5c3e-9b7f-a1e2d3c4b5a6', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'AWS の最新情報', 'AWS の公式の発表から、AI が作り、人が確かめたクイズ', 5)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order) VALUES
    ('5b9e7c2d-1a3f-5e4b-8c6d-f0a1b2c3d4e5', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '2f6c1b8e-4d0a-5c3e-9b7f-a1e2d3c4b5a6', 'ニュース', 1, 1)
    ON CONFLICT (id) DO NOTHING;

-- 地理の勉強会 ----------------------------------------------------------------
-- 扱う分野がテナントごとに違ってよいことを示す。アプリは分野を前提にしない
SELECT set_config('app.tenant_id', '9efd94a0-e517-573b-b46e-0f1ae96fc342', true);

INSERT INTO quiz.categories (id, tenant_id, name, description, sort_order) VALUES
    ('c6e82f53-2040-54ae-aa18-38a0c3ed1190', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '世界の首都', '最大の都市と首都が違う国', 1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order) VALUES
    ('01fbeaec-ab82-52f9-9c52-8e5c4784f6d0', '9efd94a0-e517-573b-b46e-0f1ae96fc342', 'c6e82f53-2040-54ae-aa18-38a0c3ed1190', '基本', 1, 1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status) VALUES
    ('8a0fe98a-5ca7-526d-bc59-143bab566d83', '9efd94a0-e517-573b-b46e-0f1ae96fc342', 'c6e82f53-2040-54ae-aa18-38a0c3ed1190', '01fbeaec-ab82-52f9-9c52-8e5c4784f6d0',
     'オーストラリアの首都はどれですか。',
     '最大の都市はシドニーですが、首都はキャンベラです。シドニーとメルボルンが首都の座を争ったため、両都市の間に計画都市として建設されました。', 'published'),
    ('6d5297ad-1687-506d-a0a2-7c005083d77b', '9efd94a0-e517-573b-b46e-0f1ae96fc342', 'c6e82f53-2040-54ae-aa18-38a0c3ed1190', '01fbeaec-ab82-52f9-9c52-8e5c4784f6d0',
     'カナダの首都はどれですか。',
     '最大の都市はトロントですが、首都はオタワです。英語圏のオンタリオ州とフランス語圏のケベック州の境に位置しています。', 'published'),
    ('39571769-55b9-567a-84e4-37348b49d135', '9efd94a0-e517-573b-b46e-0f1ae96fc342', 'c6e82f53-2040-54ae-aa18-38a0c3ed1190', '01fbeaec-ab82-52f9-9c52-8e5c4784f6d0',
     'トルコの首都はどれですか。',
     '最大の都市はイスタンブールですが、首都はアンカラです。1923 年、共和国の成立にあわせてアナトリア中央部のアンカラが首都に定められました。', 'published')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
SELECT v.id::uuid, v.tenant_id::uuid, v.quiz_id::uuid, v.body, v.is_correct, v.sort_order FROM (VALUES
    ('ec6e78df-4d7e-5fd6-b8ee-4e62396e5698', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '8a0fe98a-5ca7-526d-bc59-143bab566d83', 'キャンベラ', true, 1),
    ('18264ac8-8e4e-50d7-9232-7db52cf0d4f8', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '8a0fe98a-5ca7-526d-bc59-143bab566d83', 'シドニー', false, 2),
    ('ffcefcc0-7acd-508f-90cd-49bbf6317aa9', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '8a0fe98a-5ca7-526d-bc59-143bab566d83', 'メルボルン', false, 3),
    ('4df5b18a-737c-5b4c-b4d8-a0ab70a3c492', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '8a0fe98a-5ca7-526d-bc59-143bab566d83', 'パース', false, 4),
    ('b3634b63-ce42-5604-b567-adcf0ae8181c', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '6d5297ad-1687-506d-a0a2-7c005083d77b', 'トロント', false, 1),
    ('ed30719c-d15a-563e-9bfc-a9c5321c63c2', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '6d5297ad-1687-506d-a0a2-7c005083d77b', 'オタワ', true, 2),
    ('292f4cef-193d-5a80-af7f-63b3898e15ea', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '6d5297ad-1687-506d-a0a2-7c005083d77b', 'バンクーバー', false, 3),
    ('e2805e4c-c84a-5eff-a73c-cd938cf73568', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '6d5297ad-1687-506d-a0a2-7c005083d77b', 'モントリオール', false, 4),
    ('1733cce1-9252-5c40-8faf-a3919bb463b0', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '39571769-55b9-567a-84e4-37348b49d135', 'イスタンブール', false, 1),
    ('050896ba-a08f-568e-89d4-07369127857d', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '39571769-55b9-567a-84e4-37348b49d135', 'イズミル', false, 2),
    ('0afb6125-fa30-523e-841b-4703dd446d52', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '39571769-55b9-567a-84e4-37348b49d135', 'アンカラ', true, 3),
    ('240939f6-719b-58df-b8d4-3182f3d35560', '9efd94a0-e517-573b-b46e-0f1ae96fc342', '39571769-55b9-567a-84e4-37348b49d135', 'アンタルヤ', false, 4)
    ) AS v (id, tenant_id, quiz_id, body, is_correct, sort_order)
    WHERE NOT EXISTS (SELECT 1 FROM quiz.choices c WHERE c.quiz_id = v.quiz_id::uuid)
    ON CONFLICT (id) DO NOTHING;
