-- テナントの Slack の通知先を設定したかどうか（DEV-102、ADR-0022）。
--
-- **URL そのものは持たない。** URL は SSM Parameter Store の SecureString に置き、quiz-service は書くことと消すことしかできない。
-- 設定したかどうかを SSM に問い合わせるには、読む権限が要る。AWS 管理のキー（aws/ssm）では、読める者は値まで読めてしまう。
-- そのため、設定したという印と日時だけを DB に持つ。
--
-- 行があれば設定済み。消したら行も消す。履歴ではなく設定なので、論理削除にしない（ADR-0007 の対象外）。
-- 通知するのはクイズのイベントのため、quiz スキーマに置く。テナント配下の表として行レベルセキュリティを付ける
CREATE TABLE quiz.slack_webhooks (
    tenant_id     uuid        PRIMARY KEY REFERENCES core.tenants (id),
    configured_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE quiz.slack_webhooks ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.slack_webhooks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.slack_webhooks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
