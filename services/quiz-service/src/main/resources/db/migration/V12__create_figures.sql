-- 解説図（ADR-0017）。本体（draw.io の原本と、書き出した SVG）は S3 に置き、ここには行だけを持つ。
--
-- **誰に返すかは、この行が見えるかどうかで決める。** 行レベルセキュリティの下で、別テナントの図の行は見えない。
-- S3 のキーにもテナントを含めるが、それは行の確かめをすり抜けたときの 2 段目の守り。
--
-- 図は一度置いたら変えない。描き直した図は新しい行になるため、updated_at を持たない。
-- ID はアプリが作る。S3 に本体を置いてから行を入れるため、行より先に ID が要る
CREATE TABLE quiz.figures (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES core.tenants (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE quiz.figures ENABLE ROW LEVEL SECURITY;
ALTER TABLE quiz.figures FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quiz.figures
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
