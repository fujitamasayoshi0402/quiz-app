-- イベントを書いたときのトレースの文脈（W3C の traceparent。ADR-0026）。
-- 送るとき、EventBridge の PutEvents に X-Ray のトレースヘッダとして渡す。受け手の Lambda が同じトレースにつながる。
-- トレースを取っていないとき（ローカル、テスト、移行前の行）は NULL で、イベントはそのまま送る
ALTER TABLE quiz.outbox ADD COLUMN trace_parent text;
