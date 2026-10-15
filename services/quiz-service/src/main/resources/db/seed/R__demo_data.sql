-- デモ用のシードデータ。**アプリの仕様ではなくサンプル**（DEV-31）。
--
-- 投入方法は Flyway の repeatable マイグレーション。
-- 開発プロファイルでのみ locations に classpath:db/seed が加わるため、本番では読み込まれない
-- （二重の防御として com.quizapp.startup.SeedDataGuard が起動時に確かめる）。
--
-- 何度流しても増えないよう、識別子を固定して ON CONFLICT DO NOTHING で入れる。
-- repeatable マイグレーションは内容を変えると再実行されるため、この性質が要る。
--
-- クイズを足すときは、識別子を新しく採番してこのファイルに追記する。

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

-- カテゴリ --------------------------------------------------------------------
INSERT INTO quiz.categories (id, tenant_id, name, description, sort_order) VALUES
    ('63b3c609-bfa5-5db4-bc9a-ec709936969d', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'AWS / インフラ', 'クラウド基盤の設計と運用', 1),
    ('639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '認証認可', '認証・認可まわりの設計', 2),
    ('0ce3f934-9f5b-5803-ac80-fb15da5aea62', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'イベント駆動設計', '非同期連携とマイクロサービス', 3)
    ON CONFLICT (id) DO NOTHING;

-- 難易度。**カテゴリごとに体系が違う**ことと、
-- 同じレベルに複数の難易度が並びうること（SAA / DVA）を示すサンプルになっている
INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order) VALUES
    ('c39ca199-7f94-5d1b-b862-9667de4ba6c9', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'CLF', 1, 1),
    ('27cddecf-6dcc-5a94-b3c5-e8996238fbfd', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'SAA', 2, 1),
    ('85e8172b-d967-59f2-b1c4-04bba85d1d73', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'DVA', 2, 2),
    ('dfca97c2-0afe-5e32-9c1f-54c83a0041f8', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'SAP', 3, 1),
    ('f39b360e-f652-5e37-8b52-f70cf9c78211', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', '初級', 1, 1),
    ('2481b9a0-1117-5bb6-b4ce-5ed59da4ead5', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', '中級', 2, 1),
    ('fae4d4f9-4324-5688-ad6b-0071be243984', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', '上級', 3, 1),
    ('8a5d77d1-a61f-50e6-a117-d239cdd6e8cb', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0ce3f934-9f5b-5803-ac80-fb15da5aea62', '基礎', 1, 1),
    ('e5cd4a46-9cec-5ece-a6c1-61c10447b558', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0ce3f934-9f5b-5803-ac80-fb15da5aea62', '応用', 2, 1)
    ON CONFLICT (id) DO NOTHING;

-- クイズ ----------------------------------------------------------------------
INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status) VALUES
    ('808eab59-8191-526d-8019-0ffb31f67840', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'c39ca199-7f94-5d1b-b862-9667de4ba6c9',
     'AWS の責任共有モデルにおいて、利用者側が責任を負うのはどれですか。',
     'AWS が「クラウド“の”セキュリティ」、利用者が「クラウド“内”のセキュリティ」を担います。EC2 は IaaS なので、OS より上のレイヤ（パッチ適用・ミドルウェア・データ）は利用者の責任です。データセンターの物理セキュリティ、ハイパーバイザー、基盤ハードウェアの保守は AWS の責任範囲にあたります。', 'published'),
    ('efa903bc-a659-5558-945d-871203889a7d', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', 'dfca97c2-0afe-5e32-9c1f-54c83a0041f8',
     '複数アカウントの CloudTrail ログを一元管理したい。適切な構成はどれですか。',
     'AWS Organizations の管理アカウントで組織の証跡を作ると、メンバーアカウントの API 呼び出しが自動的に集約されます。保存先をログアーカイブ専用アカウントの S3 に分けておくと、侵害されたアカウントからログを消せません。アカウントごとの個別設定は、追加時の設定漏れがそのまま監査の穴になります。', 'published'),
    ('7a21cbf6-795e-5532-a759-6c7e94584bd7', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', 'f39b360e-f652-5e37-8b52-f70cf9c78211',
     '認証（Authentication）と認可（Authorization）の違いとして正しいものはどれですか。',
     '認証は「誰であるか」を確かめる手続き、認可は「その人が何をしてよいか」を決める手続きです。順序としては認証が先に来ますが、別の関心事なので実装も分けて考えます。同じ人でも文脈によって権限は変わります（このアプリでもテナントごとにロールが決まります）。', 'published'),
    ('7a3075f5-e0bc-5f6a-85b5-b3a5493c9388', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', '2481b9a0-1117-5bb6-b4ce-5ed59da4ead5',
     'OAuth 2.0 の認可コードフローで PKCE が必要とされる理由はどれですか。',
     'PKCE では code_verifier という乱数と、そのハッシュである code_challenge を使います。認可コードを横取りされても、攻撃者は code_verifier を知らないためトークンと交換できません。クライアントシークレットを安全に保持できないネイティブアプリや SPA で特に重要ですが、現在は Web アプリでも推奨されています。', 'published'),
    ('bfd07645-c30e-5d72-98cd-40f257467313', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '639ececc-e1c2-559c-9acb-7d9cfa4b1bc8', 'fae4d4f9-4324-5688-ad6b-0071be243984',
     'パスキー（WebAuthn）がフィッシングに強いとされる理由はどれですか。',
     'WebAuthn ではクレデンシャルが Relying Party ID（ドメイン）に紐づきます。偽サイトから認証を求められても、認証器は正規ドメイン向けの署名を作らないため、利用者が騙されても認証情報は渡りません。ワンタイムコードは利用者が偽サイトに入力できてしまうため、この点が決定的に異なります。', 'published'),
    ('e104a342-ab08-51ee-b8a7-e3300830ee8b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0ce3f934-9f5b-5803-ac80-fb15da5aea62', '8a5d77d1-a61f-50e6-a117-d239cdd6e8cb',
     '同期的な呼び出しではなく、非同期のイベント連携にすることで得られるものはどれですか。',
     'イベント駆動は送信側と受信側を時間的に切り離します。受信側が停止していてもイベントは残り、復旧後に処理できます。代わりに結果整合性になるため、「いつ反映されるか」を前提にした画面や仕様は作れません。全体の処理時間が短くなるとは限らず、むしろ反映までの時間は伸びることもあります。', 'published'),
    ('e69a504f-d462-5738-a130-4a52bb19ab6b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0ce3f934-9f5b-5803-ac80-fb15da5aea62', 'e5cd4a46-9cec-5ece-a6c1-61c10447b558',
     '複数のマイクロサービスにまたがる更新を扱う、代表的な方式はどれですか。',
     '分散トランザクション（2 相コミット）は可用性とスケーラビリティを損なうため、マイクロサービスでは避けられることが多い方式です。Saga は一連のローカルトランザクションを順に実行し、途中で失敗したら補償トランザクションで打ち消します。DB を共有するとサービス境界が消えるため、分割した意味がなくなります。', 'published'),
    ('8d4f8264-ecb2-583b-b69b-1a40071c3fa6', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '0ce3f934-9f5b-5803-ac80-fb15da5aea62', 'e5cd4a46-9cec-5ece-a6c1-61c10447b558',
     '【下書きの例】イベントのスキーマを変更するとき、互換性を保つ基本方針はどれですか。',
     '作成途中のクイズです。下書きは出題されません。', 'draft')
    ON CONFLICT (id) DO NOTHING;

-- 選択肢。正解は 1 クイズにつき 1 つ（部分ユニークインデックスが保証する）。
-- **選択肢がまだ無いクイズにだけ入れる。** 画面でクイズを編集すると、選択肢は新しい識別子で入れ直される。
-- 識別子だけで重複を避けると、流し直したときにシードの選択肢が足され、正解が 2 つになって一意制約で落ちる（DEV-104 のデプロイ）。
-- 選択肢の識別子以外の衝突まで ON CONFLICT DO NOTHING で避けると、正解でない選択肢だけが足されて数が増える
INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
SELECT v.id::uuid, v.tenant_id::uuid, v.quiz_id::uuid, v.body, v.is_correct, v.sort_order FROM (VALUES
    ('6b439e75-f3f3-5ad4-bae0-9715fbd5627b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '808eab59-8191-526d-8019-0ffb31f67840',
     'EC2 インスタンス上の OS へのパッチ適用', true, 1),
    ('04aa9adf-d32d-5e0c-8d11-66cabf596dd2', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '808eab59-8191-526d-8019-0ffb31f67840',
     'データセンターの物理的な入退室管理', false, 2),
    ('f2b7e6e0-50c8-5e71-84f5-406b2c48e0c7', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '808eab59-8191-526d-8019-0ffb31f67840',
     'ハイパーバイザーの脆弱性対応', false, 3),
    ('87f2eef7-cb12-5793-a00b-fd84a702fa63', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '808eab59-8191-526d-8019-0ffb31f67840',
     'マネージドサービスの基盤ハードウェアの保守', false, 4),
    ('4b8ddde3-ddf5-5952-ba7a-9c1feafb2505', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'efa903bc-a659-5558-945d-871203889a7d',
     '組織の証跡を作り、ログアーカイブ用アカウントの S3 に集約する', true, 1),
    ('76950d06-a94d-5d5b-afc0-c39c54b0ca58', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'efa903bc-a659-5558-945d-871203889a7d',
     '各アカウントで個別に証跡を作り、定期的に手動でコピーする', false, 2),
    ('a0f2b3a2-654e-5693-8466-e9ed835bca0b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'efa903bc-a659-5558-945d-871203889a7d',
     'すべてのアカウントで同じ IAM ユーザーを共有する', false, 3),
    ('0fc818c3-6ffe-5a11-9fbd-48db496280c8', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'efa903bc-a659-5558-945d-871203889a7d',
     'ログを CloudWatch Logs にのみ保存する', false, 4),
    ('b70622dd-e570-56a0-b8fb-8799dba96c6b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a21cbf6-795e-5532-a759-6c7e94584bd7',
     '認証は「誰か」を確かめ、認可は「何をしてよいか」を決める', true, 1),
    ('c941dbbe-bc4b-5aa0-84f3-41e306b3e6c5', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a21cbf6-795e-5532-a759-6c7e94584bd7',
     '認証はサーバー側、認可はクライアント側で行う', false, 2),
    ('20a6ad21-6ba7-5fef-b4b5-c236207a282f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a21cbf6-795e-5532-a759-6c7e94584bd7',
     '認証はパスワード、認可は生体情報を使う', false, 3),
    ('5a84a142-addf-5f8f-bb22-89d6fda58b9f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a21cbf6-795e-5532-a759-6c7e94584bd7',
     '認証は初回のみ、認可は毎回行う', false, 4),
    ('7322d323-f3b4-5654-b885-3a4f1c59308f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a3075f5-e0bc-5f6a-85b5-b3a5493c9388',
     '認可コードを傍受されても、検証値を知らない攻撃者はトークンと交換できないため', true, 1),
    ('3c7fc12d-e0d3-565b-a0de-546263a677eb', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a3075f5-e0bc-5f6a-85b5-b3a5493c9388',
     'アクセストークンを暗号化するため', false, 2),
    ('fa8d2399-5348-5f50-953c-13bb4fc0d0dd', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a3075f5-e0bc-5f6a-85b5-b3a5493c9388',
     'リフレッシュトークンの有効期限を延ばすため', false, 3),
    ('eff8e200-66a9-5244-96be-2f786ca1c296', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a3075f5-e0bc-5f6a-85b5-b3a5493c9388',
     'スコープを動的に変更するため', false, 4),
    ('6add72b1-33f1-5563-978a-ed9c2c0a33dc', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'bfd07645-c30e-5d72-98cd-40f257467313',
     '認証器が鍵をドメインに紐づけ、別ドメインには応答しないため', true, 1),
    ('71b48fc0-2219-51b9-841f-3cff79cfca6c', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'bfd07645-c30e-5d72-98cd-40f257467313',
     'パスワードより文字数が長いため', false, 2),
    ('8118d4a9-7311-5402-bd1a-43435c50b395', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'bfd07645-c30e-5d72-98cd-40f257467313',
     '通信が常に TLS で暗号化されるため', false, 3),
    ('666e3ba4-44b4-577f-bb56-28758522935d', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'bfd07645-c30e-5d72-98cd-40f257467313',
     '認証のたびにワンタイムコードを送るため', false, 4),
    ('57d5bc60-712a-5294-9ad1-47efebaab364', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e104a342-ab08-51ee-b8a7-e3300830ee8b',
     '送信側が受信側の障害や遅延の影響を受けにくくなる', true, 1),
    ('d5600ba7-3c56-5688-9b6f-ed0e76768a7e', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e104a342-ab08-51ee-b8a7-e3300830ee8b',
     '処理結果を即座に呼び出し元へ返せる', false, 2),
    ('b40d1ce1-25de-58ac-9a71-87c48539ca66', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e104a342-ab08-51ee-b8a7-e3300830ee8b',
     '全体の処理時間が必ず短くなる', false, 3),
    ('5541fa5f-2a33-5f74-a398-a49efe609790', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e104a342-ab08-51ee-b8a7-e3300830ee8b',
     'データの整合性が常に保たれる', false, 4),
    ('77f612ad-ad4f-55ca-aba3-0f075f1c5eed', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e69a504f-d462-5738-a130-4a52bb19ab6b',
     'Saga パターンで、ローカルトランザクションと補償処理を組み合わせる', true, 1),
    ('2c16c798-343c-5db2-9f06-f2a14d106d1f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e69a504f-d462-5738-a130-4a52bb19ab6b',
     '2 相コミットをすべてのサービスで実装する', false, 2),
    ('8cf7a102-976a-5f34-847e-bc785240580d', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e69a504f-d462-5738-a130-4a52bb19ab6b',
     'すべてのサービスで同じデータベースを共有する', false, 3),
    ('7db511e7-1fd0-53c7-b27e-276ff5ae848a', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'e69a504f-d462-5738-a130-4a52bb19ab6b',
     '更新処理だけを 1 つのサービスに集約する', false, 4),
    ('828da288-c764-59a7-89d1-192a7d56aa35', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '8d4f8264-ecb2-583b-b69b-1a40071c3fa6',
     'フィールドの追加は任意項目として行う', true, 1),
    ('3cfcd5ea-900e-50fe-a23d-94ce62b16ec6', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '8d4f8264-ecb2-583b-b69b-1a40071c3fa6',
     '既存フィールドを削除する', false, 2)
    ) AS v (id, tenant_id, quiz_id, body, is_correct, sort_order)
    WHERE NOT EXISTS (SELECT 1 FROM quiz.choices c WHERE c.quiz_id = v.quiz_id::uuid)
    ON CONFLICT (id) DO NOTHING;

-- 技術のクイズ（DEV-80） -------------------------------------------------------
-- 解説は Markdown で書く（ADR-0018）。箇条書き・表・コードを使った例も兼ねる
INSERT INTO quiz.categories (id, tenant_id, name, description, sort_order) VALUES
    ('7a8fda3d-1b93-516e-9b78-00e8dd5778aa', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'Web アプリの技術スタック', 'このアプリが使っている技術（Next.js、Kotlin / Spring Boot、PostgreSQL、Terraform、Playwright）', 4)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order) VALUES
    ('e8a95ea6-602a-5876-a26d-609b65bafbc4', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a8fda3d-1b93-516e-9b78-00e8dd5778aa', '基礎', 1, 1),
    ('ab2fda52-ae31-5363-8ad9-1f4ff2b30fe8', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a8fda3d-1b93-516e-9b78-00e8dd5778aa', '応用', 2, 1)
    ON CONFLICT (id) DO NOTHING;
INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status) VALUES
    ('c65d9b31-309b-5e21-aa27-9e821092f81f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', '27cddecf-6dcc-5a94-b3c5-e8996238fbfd',
     'Security Group とネットワーク ACL の違いとして正しいものはどれですか。',
     '| | Security Group | ネットワーク ACL |
| --- | --- | --- |
| 付ける先 | ENI（インスタンスやタスク） | サブネット |
| 戻りの通信 | 自動で通る（ステートフル） | 許可が要る（ステートレス） |
| ルール | 許可だけ | 許可と拒否。番号の小さい順に評価 |

Security Group は、送信元に**ほかの Security Group** を指定できます。「ALB からだけ受ける」を、IP アドレスに頼らずに書けます。', 'published'),
    ('1660fcfd-319b-5d23-8a81-8761fe3d6715', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '63b3c609-bfa5-5db4-bc9a-ec709936969d', '85e8172b-d967-59f2-b1c4-04bba85d1d73',
     'GitHub Actions から AWS に、アクセスキーを使わずに認証する方法はどれですか。',
     'GitHub Actions は、実行ごとに OIDC のトークンを発行します。IAM にその発行者（OIDC プロバイダ）を登録し、ロールの信頼ポリシーで条件を付けると、`AssumeRoleWithWebIdentity` で一時的な認証情報を受け取れます。

- 長く使えるアクセスキーを保管しないので、漏れたときの被害が小さい
- 信頼ポリシーで **`sub`（リポジトリ・ブランチ・Environment）を絞る**。絞らないと、ほかのリポジトリのワークフローからも引き受けられてしまう', 'published'),
    ('92f6b1e3-91c2-559b-9e5d-fffd58d8e859', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a8fda3d-1b93-516e-9b78-00e8dd5778aa', 'e8a95ea6-602a-5876-a26d-609b65bafbc4',
     'TanStack Query の `queryKey` の役割として正しいものはどれですか。',
     '同じ `queryKey` を使うコンポーネントは同じキャッシュを読み、同時に出た問い合わせは 1 回にまとめられます。データを変えたあとは、`invalidateQueries` でキーを指定して捨て、取り直させます。

キャッシュは利用者ごとではなく、ブラウザの中に 1 つです。このアプリは、ログインし直した利用者に前の利用者の応答が見えないよう、利用者が替わるとキャッシュを作り直しています。', 'published'),
    ('98f96866-6660-5c9e-a81e-8a31da0db8a1', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '7a8fda3d-1b93-516e-9b78-00e8dd5778aa', 'ab2fda52-ae31-5363-8ad9-1f4ff2b30fe8',
     'PostgreSQL の行レベルセキュリティ（RLS）で、`FORCE ROW LEVEL SECURITY` を付ける理由はどれですか。',
     '`ENABLE ROW LEVEL SECURITY` だけでは、**テーブルの所有者はポリシーを素通り**します。所有者のロールで接続する処理（マイグレーションなど）があるなら、`FORCE` を付けないと守りが効きません。

ただし、スーパーユーザーと `BYPASSRLS` を持つロールは、`FORCE` を付けても素通りします。このアプリは、アプリが接続するロール（`quiz_app`）をスーパーユーザーにせず、テーブルの所有者とも分けています。', 'published')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO quiz.choices (id, tenant_id, quiz_id, body, is_correct, sort_order)
SELECT v.id::uuid, v.tenant_id::uuid, v.quiz_id::uuid, v.body, v.is_correct, v.sort_order FROM (VALUES
    ('cd3ecaa1-3b95-5004-97ec-26744d0ae87f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'c65d9b31-309b-5e21-aa27-9e821092f81f',
     'Security Group はステートフルで許可のルールだけを書き、ネットワーク ACL はステートレスで拒否のルールも書ける', true, 1),
    ('93865a00-a783-5540-aa33-784af28bb104', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'c65d9b31-309b-5e21-aa27-9e821092f81f',
     'Security Group はサブネット単位、ネットワーク ACL はインスタンス単位で効く', false, 2),
    ('626ad3a8-e2b2-5a58-91ce-9a8c3ecf9480', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'c65d9b31-309b-5e21-aa27-9e821092f81f',
     'どちらもステートレスで、戻りの通信にも許可のルールが要る', false, 3),
    ('9e9aeae4-c035-5cfc-80ee-973cbfe5170b', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', 'c65d9b31-309b-5e21-aa27-9e821092f81f',
     'ネットワーク ACL は、ほかの Security Group を送信元として指定できる', false, 4),
    ('db0d7a80-ea2f-5a91-9333-6eb9a197276f', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '1660fcfd-319b-5d23-8a81-8761fe3d6715',
     'アクセスキーを GitHub の secret に入れ、90 日ごとに入れ替える', false, 1),
    ('afb537c7-6cca-5ed2-9909-9f5b26457a42', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '1660fcfd-319b-5d23-8a81-8761fe3d6715',
     'GitHub の OIDC プロバイダを IAM に登録し、ワークフローが発行するトークンでロールを引き受ける', true, 2),
    ('4d3ff9b0-1d5e-5e81-ae59-1d7db48f5242', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '1660fcfd-319b-5d23-8a81-8761fe3d6715',
     'ルートユーザーの MFA のコードを、ワークフローに渡す', false, 3),
    ('09f02508-4499-5272-b94f-d132886c823a', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '1660fcfd-319b-5d23-8a81-8761fe3d6715',
     'IAM ユーザーのパスワードで、コンソールにログインさせる', false, 4),
    ('e5a9d2ca-2220-512d-bbaa-0e6ae407cca9', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '92f6b1e3-91c2-559b-9e5d-fffd58d8e859',
     'API の URL を暗号化するための値', false, 1),
    ('511687c6-5145-5402-85d7-717329c771fc', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '92f6b1e3-91c2-559b-9e5d-fffd58d8e859',
     'キャッシュを識別するキーで、同じキーの問い合わせは結果を共有し、キーを指定して無効にできる', true, 2),
    ('2cc391b4-21c2-565e-bcd7-2cec4192ae84', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '92f6b1e3-91c2-559b-9e5d-fffd58d8e859',
     '問い合わせを実行する順番を決める番号', false, 3),
    ('954983cc-f4cc-549f-be38-b2ebdf98ddff', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '92f6b1e3-91c2-559b-9e5d-fffd58d8e859',
     'サーバー側のキャッシュを消すための鍵', false, 4),
    ('df258311-33c7-518a-ae22-e18298e8cc40', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '98f96866-6660-5c9e-a81e-8a31da0db8a1',
     'スーパーユーザーにもポリシーを効かせるため', false, 1),
    ('8970f2ce-f346-5309-a5cd-b70996414fcf', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '98f96866-6660-5c9e-a81e-8a31da0db8a1',
     'ポリシーの評価を速くするため', false, 2),
    ('818ac638-1f8e-5da9-961c-65951f7872f2', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '98f96866-6660-5c9e-a81e-8a31da0db8a1',
     'RLS を有効にしたテーブルで、INSERT を禁止するため', false, 3),
    ('163bbeaf-2f5d-50b0-b79a-8663994df083', '7fd43527-dbbf-525e-9f33-f48e4e507fd1', '98f96866-6660-5c9e-a81e-8a31da0db8a1',
     'テーブルの所有者にもポリシーを効かせるため', true, 4)
    ) AS v (id, tenant_id, quiz_id, body, is_correct, sort_order)
    WHERE NOT EXISTS (SELECT 1 FROM quiz.choices c WHERE c.quiz_id = v.quiz_id::uuid)
    ON CONFLICT (id) DO NOTHING;

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
