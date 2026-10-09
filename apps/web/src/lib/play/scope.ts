/** 出題対象。選ぶ画面と、履歴の表示で同じ言葉を使う */
export const SCOPES = [
  { value: "all", label: "すべて", hint: "回答済みの問題も含める" },
  { value: "unanswered", label: "未回答を優先", hint: "足りない分は回答済みで埋める" },
  { value: "unanswered_only", label: "未回答のみ", hint: "一周したいときに" },
];

export function scopeLabel(value: string): string {
  return SCOPES.find((scope) => scope.value === value)?.label ?? value;
}
