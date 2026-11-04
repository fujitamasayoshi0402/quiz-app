-- DEV-222: テナントの数の上限（ADR-0028）を受けない利用者の印。
-- この印のある人が作ったテナント（core.tenants.created_by）は、quiz_limit / figure_limit の値によらず上限なしとして扱う。
-- 運用者が自分のテナントに使う。画面や API からは付けられず、Data API で 1 行だけ書き換える（Runbook「テナントの上限を変える」）。
ALTER TABLE core.users ADD COLUMN capacity_exempt boolean NOT NULL DEFAULT false;
