-- 出題対象に「間違えた問題」（incorrect）を足す（DEV-147）。
--
-- 制約を張り直すだけで、既存の行は変わらない。
-- 1 つ前のアプリはこの値を書かない。入れ替えの間に、古いタスクが新しいタスクの作った
-- incorrect の挑戦を読むと、知らない値として失敗する。入れ替えは数分で終わるため受け入れる
ALTER TABLE answer.attempts
    DROP CONSTRAINT attempts_scope_check,
    ADD CONSTRAINT attempts_scope_check
        CHECK (scope IN ('all', 'unanswered', 'unanswered_only', 'incorrect'));
