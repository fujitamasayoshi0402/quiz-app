-- README の画面を撮るためのデータ（DEV-180）。capture/global-setup.ts が、fixtures.sql のあとに流す。何度流しても同じ状態になる。
--
-- 撮るのはデモのテナント（シードの R__demo_data.sql）。見に来た人が見るものと同じ画面にする。
-- E2E の管理者を、デモのテナントの管理者にもする。シードのデモ管理者は、ローカルではログインできない
INSERT INTO core.tenant_members (tenant_id, user_id, role)
SELECT t.id, u.id, 'admin'
FROM core.users u
JOIN core.tenants t ON t.slug = 'demo' AND t.deleted_at IS NULL
WHERE lower(u.email) = 'e2e-admin@example.com'
ON CONFLICT (tenant_id, user_id) WHERE deleted_at IS NULL DO NOTHING;

-- AI が技術ニュースから作った下書き（ADR-0031）。受け箱から取り込んだときと同じく、出典の URL を持つ下書きにする。
-- 管理者の GIF が公開するので、毎回下書きに戻す
INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status, source_url)
VALUES ('5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911', '7fd43527-dbbf-525e-9f33-f48e4e507fd1',
        '2f6c1b8e-4d0a-5c3e-9b7f-a1e2d3c4b5a6', '5b9e7c2d-1a3f-5e4b-8c6d-f0a1b2c3d4e5',
        'Amazon ECS の新しいメトリクス LiveTaskCount は、何を数えますか。',
        E'サービスの中で、実際に動いているタスクの数を数えます。起動の途中や、止まりかけのタスクは数えません。\n\n出典: [Amazon ECS adds LiveTaskCount](https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/)',
        'draft', 'https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/')
ON CONFLICT (id) DO UPDATE
SET question = EXCLUDED.question, explanation = EXCLUDED.explanation, status = 'draft', deleted_at = NULL;

-- 編集して保存すると、選択肢は新しい行に替わる。前の回の行を消してから入れ直す
DELETE FROM quiz.choices WHERE quiz_id = '5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911';

INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
VALUES ('a3e0c6d1-5b2f-4c8e-9d7a-1f4b6e2c8a01', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911', '実際に動いているタスクの数', true, 1),
       ('a3e0c6d1-5b2f-4c8e-9d7a-1f4b6e2c8a02', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911', '動かそうとしているタスクの数', false, 2),
       ('a3e0c6d1-5b2f-4c8e-9d7a-1f4b6e2c8a03', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911', '起動に失敗したタスクの数', false, 3),
       ('a3e0c6d1-5b2f-4c8e-9d7a-1f4b6e2c8a04', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '5c1f0e2a-8d47-4b6e-9a3c-2f71d0b8e911', '登録されているコンテナインスタンスの数', false, 4);
