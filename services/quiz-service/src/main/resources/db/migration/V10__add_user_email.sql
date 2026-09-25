-- 利用者のメールアドレス（ADR-0016）。認証基盤で確認済みのものだけを持つ。
--
-- - 招待を受け入れるときに、招待したアドレスと比べる
-- - 認証基盤を替えるときに、既存の利用者に結び直す
-- - 事前に登録した利用者（最初の管理者、スモークテスト）を、認証基盤の ID を知らずに用意する
--
-- 1 つ前のアプリはこの列を知らずに動く。既定値のない NULL 許容の列なので、そのまま両立する

ALTER TABLE core.users ADD COLUMN email text;

-- 大文字と小文字を区別しない。認証基盤もアドレスを大文字と小文字で区別しない
CREATE UNIQUE INDEX users_email_key ON core.users (lower(email));

-- 事前に登録した利用者は、最初にログインするまで認証基盤の ID を持たない。
-- どちらも持たない利用者は、誰にも結び付けられないため許さない
ALTER TABLE core.users ALTER COLUMN external_id DROP NOT NULL;
ALTER TABLE core.users ADD CONSTRAINT users_identifiable CHECK (external_id IS NOT NULL OR email IS NOT NULL);
