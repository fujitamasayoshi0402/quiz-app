-- 見学者のロール（DEV-196、ADR-0035）。管理画面を読むだけで、変更はできない。出題は一般ユーザーと同じく使える。
--
-- 招待では渡せない（invitations_role は admin と member のまま）。見学者にするのは運用者で、シードで入れる。
-- 制約を張り直す間、表を短くロックする。所属の行は少なく、すぐに終わる
ALTER TABLE core.tenant_members DROP CONSTRAINT tenant_members_role;
ALTER TABLE core.tenant_members
    ADD CONSTRAINT tenant_members_role CHECK (role IN ('admin', 'member', 'viewer'));
