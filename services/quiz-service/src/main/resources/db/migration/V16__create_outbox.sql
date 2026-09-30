-- クイズのイベントの Outbox（ADR-0022）。
--
-- **クイズの変更と同じトランザクションで書く。** 変更だけが残ってイベントが失われる、またはその逆を起こさない。
-- 送るのはコミットの後で、送れたら published_at を付ける（DEV-97）。送れた行は 7 日で消す。
--
-- id はイベントの ID（eventId）。アプリが作り、payload にも同じ値を入れる。何度送り直しても変わらないため、受け手が重複を捨てる鍵になる。
-- payload は EventBridge の detail、event_type は detail-type にそのまま使う
CREATE TABLE quiz.outbox (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES core.tenants (id),
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    occurred_at  timestamptz NOT NULL,
    published_at timestamptz
);

-- 送れていないものを古い順に拾う（DEV-97）
CREATE INDEX outbox_unpublished_idx ON quiz.outbox (occurred_at) WHERE published_at IS NULL;

ALTER TABLE quiz.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.outbox
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 送れなかったものの拾い直し（DEV-97）だけは、テナントをまたいで読み、送れた印を付け、古い行を消す。
-- **セッション変数 app.outbox_relay を 'on' にしたときだけ**、テナントを問わずに見せる。立てるのは拾い直しの 1 か所だけ。
-- ほかのコードが絞り込みを書き漏らしても、別のテナントの行は返らない。
--
-- 書き込み（INSERT）は許さない。イベントを書くのは、テナントの決まった操作だけ。
-- 複数の許可ポリシーは OR で効くため、コマンドごとに分けて INSERT を含めない
CREATE POLICY outbox_relay_select ON quiz.outbox FOR SELECT
    USING (current_setting('app.outbox_relay', true) = 'on');
CREATE POLICY outbox_relay_update ON quiz.outbox FOR UPDATE
    USING (current_setting('app.outbox_relay', true) = 'on')
    WITH CHECK (current_setting('app.outbox_relay', true) = 'on');
CREATE POLICY outbox_relay_delete ON quiz.outbox FOR DELETE
    USING (current_setting('app.outbox_relay', true) = 'on');
