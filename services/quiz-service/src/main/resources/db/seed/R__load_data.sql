-- 負荷試験（tests/load）が使うテナントと利用者とクイズ。**アプリの仕様ではなくテストの足場**（DEV-112）。
--
-- 利用者ごとに流量の上限（5 件/秒）があるため、1 人では負荷にならない。一般ユーザーを 50 人と、管理者を 1 人入れる。
-- 利用者はメールアドレスだけで登録しておき、同じアドレスの Cognito の利用者（Terraform の modules/auth が作る）が
-- 最初にログインしたときに結び付く（R__smoke_data.sql と同じ）。
--
-- クイズは試験が作らず、ここで入れる。出題と回答を繰り返すだけで、作っては消すことはしない。
-- 回答と履歴は試験のたびに増える。実際の利用でも増えていくものなので、消さない。
--
-- 投入の方法と、何度流しても増えない理由は R__demo_data.sql と同じ。
-- 識別子は、名前の md5 から作る。何度流しても同じ値になる。

INSERT INTO core.tenants (id, slug, name) VALUES
    ('619c1526-2954-4876-bd19-7b878f9172c5', 'load', '負荷試験')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO core.users (id, external_id, email, display_name) VALUES
    ('b72547f1-c7cc-47fa-9517-e7a0383e75bb', NULL, 'load-admin@example.com', '負荷試験の管理者')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO core.users (id, external_id, email, display_name)
SELECT md5('load-user-' || n)::uuid, NULL, format('load-%s@example.com', lpad(n::text, 2, '0')), format('負荷試験 %s', n)
FROM generate_series(1, 50) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role) VALUES
    ('619c1526-2954-4876-bd19-7b878f9172c5', 'b72547f1-c7cc-47fa-9517-e7a0383e75bb', 'admin')
    ON CONFLICT DO NOTHING;

INSERT INTO core.tenant_members (tenant_id, user_id, role)
SELECT '619c1526-2954-4876-bd19-7b878f9172c5', md5('load-user-' || n)::uuid, 'member'
FROM generate_series(1, 50) AS n
ON CONFLICT DO NOTHING;

-- テナント配下の行は、テナントを決めてから入れる（R__demo_data.sql を参照）
SELECT set_config('app.tenant_id', '619c1526-2954-4876-bd19-7b878f9172c5', true);

-- カテゴリ 3 つ × 難易度 3 つ × クイズ 20 問 = 180 問。デモのテナント（約 40 問）より多くし、一覧と出題の選び方に量を与える
INSERT INTO quiz.categories (id, tenant_id, name, description, sort_order)
SELECT md5('load-category-' || c)::uuid, '619c1526-2954-4876-bd19-7b878f9172c5',
       format('カテゴリ %s', c), '負荷試験のためのカテゴリ', c
FROM generate_series(1, 3) AS c
ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order)
SELECT md5(format('load-difficulty-%s-%s', c, d))::uuid, '619c1526-2954-4876-bd19-7b878f9172c5',
       md5('load-category-' || c)::uuid, format('レベル %s', d), d, 1
FROM generate_series(1, 3) AS c, generate_series(1, 3) AS d
ON CONFLICT (id) DO NOTHING;

-- 解説は、デモのクイズと同じくらいの長さにする。応答の大きさが実際と離れないように
INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status)
SELECT md5(format('load-quiz-%s-%s-%s', c, d, q))::uuid, '619c1526-2954-4876-bd19-7b878f9172c5',
       md5('load-category-' || c)::uuid, md5(format('load-difficulty-%s-%s', c, d))::uuid,
       format('負荷試験の問題（カテゴリ %s、レベル %s、%s 問目）。正しいものはどれですか。', c, d, q),
       format(E'正解は選択肢 %s です。\n\n'
              || '負荷試験のための問題で、内容に意味はありません。解説の長さは、デモのテナントのクイズに合わせています。'
              || E'出題、回答、採点、結果の表示で、実際の利用と同じくらいの大きさの応答を返すためです。\n\n'
              || E'- 1 つ目の項目\n- 2 つ目の項目\n- 3 つ目の項目', q % 4 + 1),
       'published'
FROM generate_series(1, 3) AS c, generate_series(1, 3) AS d, generate_series(1, 20) AS q
ON CONFLICT (id) DO NOTHING;

-- 選択肢は、まだ選択肢が無いクイズにだけ入れる（R__demo_data.sql と同じ理由）。正解の位置は問題ごとにずらす
INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
SELECT md5(format('load-choice-%s-%s-%s-%s', c, d, q, k))::uuid, '619c1526-2954-4876-bd19-7b878f9172c5',
       md5(format('load-quiz-%s-%s-%s', c, d, q))::uuid, format('選択肢 %s', k), k = q % 4 + 1, k
FROM generate_series(1, 3) AS c, generate_series(1, 3) AS d, generate_series(1, 20) AS q, generate_series(1, 4) AS k
WHERE NOT EXISTS (
    SELECT 1 FROM quiz.choices ch WHERE ch.quiz_id = md5(format('load-quiz-%s-%s-%s', c, d, q))::uuid
)
ON CONFLICT (id) DO NOTHING;
