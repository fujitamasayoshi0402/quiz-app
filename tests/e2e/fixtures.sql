-- E2E テスト（tests/e2e）のテナントと所属。テストの前に global-setup.ts が毎回流す。何度流しても同じ状態になる。
--
-- 利用者はメールアドレスだけで登録する。Terraform が作った Cognito の利用者が最初にログインしたときに、
-- 同じアドレスのこの行に結び付く（事前の登録。開発ガイドラインの「最初の管理者」と同じ仕組み）。
-- 未所属の人と招待される人は、所属を持たない状態に戻す。前の実行で招待を受け入れていても、もう一度招待できる

INSERT INTO core.tenants (slug, name) VALUES ('e2e', 'E2E テスト') ON CONFLICT DO NOTHING;
-- 公開設定のテストは公開してから戻す。途中で止まって公開のまま残っても、非公開から始める
UPDATE core.tenants SET visibility = 'private' WHERE slug = 'e2e';

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

-- 未所属の人が作ったテナントを消す（DEV-139）。テナントの作成のテストは、決まった slug で作り、終わったら消す。
-- 途中で止まって残っても、未所属のまま、同じ slug で作り直せる状態から始める（作った人と slug の一意は、削除されていないものだけ）
UPDATE core.tenants t SET deleted_at = now()
FROM core.users u
WHERE t.created_by = u.id AND t.deleted_at IS NULL AND lower(u.email) = 'e2e-outsider@example.com';

-- デモとして入るテスト（DEV-151）が、共有のデモのアカウントで解きかけたクイズを消す。
-- 前の実行の残りがあると、出題の画面に「中断中のクイズ」が出て、同じ手順で始められない
DELETE FROM answer.attempts a
USING core.users u
WHERE a.user_id = u.id AND lower(u.email) = 'demo@example.com';

-- 前の実行が途中で止まって残った招待を取り消す。招待の一覧が空の状態から始める
UPDATE core.invitations i SET revoked_at = now()
FROM core.tenants t
WHERE i.tenant_id = t.id AND t.slug = 'e2e' AND i.accepted_at IS NULL AND i.revoked_at IS NULL;

-- 出題・回答と管理のテスト（DEV-111）。e2e のテナントのカテゴリ・クイズ・図・回答を消してから、再開のテストが解くクイズを入れる。
-- 管理のテストは決まった名前でカテゴリを作るため、前の実行が作ったものを残すと作れない。
-- 論理削除ではなく行ごと消す。論理削除だとゴミ箱にたまり、同じ名前も使えない
SELECT id AS tenant_id FROM core.tenants WHERE slug = 'e2e' \gset

DELETE FROM answer.attempts WHERE tenant_id = :'tenant_id';
DELETE FROM answer.ranking_entries WHERE tenant_id = :'tenant_id';
DELETE FROM answer.bookmarks WHERE tenant_id = :'tenant_id';
DELETE FROM quiz.quizzes WHERE tenant_id = :'tenant_id';
DELETE FROM quiz.difficulties WHERE tenant_id = :'tenant_id';
DELETE FROM quiz.categories WHERE tenant_id = :'tenant_id';
DELETE FROM quiz.figures WHERE tenant_id = :'tenant_id';

INSERT INTO quiz.categories (id, tenant_id, name)
VALUES ('fd6907c9-e750-46ec-8c9a-19d47a8f35fa', :'tenant_id', 'E2E 中断と再開');

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level)
VALUES ('74a63077-7b91-4bdf-beed-1e7739108a3d', :'tenant_id', 'fd6907c9-e750-46ec-8c9a-19d47a8f35fa', '基礎', 1);

INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status)
VALUES ('7094e19e-0922-4539-be1e-0268b88890b7', :'tenant_id', 'fd6907c9-e750-46ec-8c9a-19d47a8f35fa',
        '74a63077-7b91-4bdf-beed-1e7739108a3d', '1 週間は何日ですか。', '7 日です。', 'published'),
       ('10998312-cc5b-487c-b5dc-e16257e26e0a', :'tenant_id', 'fd6907c9-e750-46ec-8c9a-19d47a8f35fa',
        '74a63077-7b91-4bdf-beed-1e7739108a3d', '1 年は何か月ですか。', '12 か月です。', 'published');

INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
VALUES ('2add36c9-c0a6-47d0-a2e2-58007be9d60d', :'tenant_id', '7094e19e-0922-4539-be1e-0268b88890b7', '5 日', false, 1),
       ('40f6d827-753e-41c3-8f9e-477806eb2648', :'tenant_id', '7094e19e-0922-4539-be1e-0268b88890b7', '6 日', false, 2),
       ('adc950a6-57a5-4df4-808e-8ef67b9630b4', :'tenant_id', '7094e19e-0922-4539-be1e-0268b88890b7', '7 日', true, 3),
       ('fbf51adc-ffbd-4897-bfec-e20b39b7becb', :'tenant_id', '7094e19e-0922-4539-be1e-0268b88890b7', '8 日', false, 4),
       ('1204535a-bf3e-4491-9e7c-075b0d920998', :'tenant_id', '10998312-cc5b-487c-b5dc-e16257e26e0a', '10 か月', false, 1),
       ('2d696f82-6679-451b-bd7f-5da17b3ad6e4', :'tenant_id', '10998312-cc5b-487c-b5dc-e16257e26e0a', '11 か月', false, 2),
       ('cc7a7e1c-993b-4cf6-b371-d6549b600222', :'tenant_id', '10998312-cc5b-487c-b5dc-e16257e26e0a', '12 か月', true, 3),
       ('5b0c3f7e-2c1a-4d8e-9f6b-3a7d1e4c8b20', :'tenant_id', '10998312-cc5b-487c-b5dc-e16257e26e0a', '13 か月', false, 4);

-- AI が技術ニュースから作った下書き（ADR-0031、DEV-162）。受け箱から取り込んだときと同じく、出典の URL を持つ下書きにする。
-- 管理者が確かめて公開し、一般ユーザーが解く
INSERT INTO quiz.categories (id, tenant_id, name)
VALUES ('bc60ce66-bbc6-47f2-93b0-fc0a57e5e43a', :'tenant_id', 'E2E 技術ニュース');

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level)
VALUES ('3aff0fed-7ef4-439c-9529-288ba4716002', :'tenant_id', 'bc60ce66-bbc6-47f2-93b0-fc0a57e5e43a', 'ニュース', 1);

INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status, source_url)
VALUES ('04d1ad90-2503-4b3b-9fb6-261bb611ad94', :'tenant_id', 'bc60ce66-bbc6-47f2-93b0-fc0a57e5e43a', '3aff0fed-7ef4-439c-9529-288ba4716002',
        'Amazon ECS の新しいメトリクス LiveTaskCount は、何を数えますか。',
        E'サービスの中で、実際に動いているタスクの数を数えます。\n\n出典: [Amazon ECS adds LiveTaskCount](https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/)',
        'draft', 'https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/');

INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
VALUES ('466a400a-b813-46e7-a56b-6a4a708d6c5b', :'tenant_id', '04d1ad90-2503-4b3b-9fb6-261bb611ad94', '実際に動いているタスクの数', true, 1),
       ('69cf5436-260f-486e-bd4c-e609ae70cce5', :'tenant_id', '04d1ad90-2503-4b3b-9fb6-261bb611ad94', '動かそうとしているタスクの数', false, 2),
       ('dd09b336-e1a1-455e-9ff8-51da38c3bcce', :'tenant_id', '04d1ad90-2503-4b3b-9fb6-261bb611ad94', '起動に失敗したタスクの数', false, 3),
       ('52c41910-7219-4f26-bb89-5425c8b9d5c8', :'tenant_id', '04d1ad90-2503-4b3b-9fb6-261bb611ad94', '登録されているコンテナインスタンスの数', false, 4);
