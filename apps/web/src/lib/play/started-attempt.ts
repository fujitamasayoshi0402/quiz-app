/**
 * 開始応答を回答画面へ一度だけ渡す（DEV-239）。回答画面が受け取ったら消す。
 * resume のキーへ直接 setQueryData すると、先に作られたクエリの gcTime（既定5分）が
 * 最大値として残り、回答画面の gcTime: 0 を指定しても中断後に古い回答状態が再利用される。
 */
export const startedAttemptKey = (slug: string, attemptId: string) => ["started-attempt", slug, attemptId] as const;
