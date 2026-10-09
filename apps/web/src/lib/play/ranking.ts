import { z } from "zod";
import { ParticipateRequest } from "@/lib/api/generated/model";

/** 期間。サーバーは今日からさかのぼった日数で区切る（暦の週や月ではない） */
export const PERIODS = [
  { value: "7d", label: "7 日" },
  { value: "30d", label: "30 日" },
  { value: "all", label: "全期間" },
] as const;
export type Period = (typeof PERIODS)[number]["value"];

/**
 * 参加のフォーム。生成したスキーマに「前後の空白を除いて 1 文字以上」を足す。
 * 長さの上限は、空白を除いたあとの長さで確かめる（サーバーと同じ）。
 */
export const ParticipationFormSchema = ParticipateRequest.extend({
  name: z.string().trim().min(1, "名前を入力してください").pipe(ParticipateRequest.shape.name),
});
export type ParticipationFormInput = z.input<typeof ParticipationFormSchema>;
export type ParticipationFormValues = z.output<typeof ParticipationFormSchema>;

/** 正答率（%）。解いていなければ null */
export function accuracy(correctCount: number, answeredCount: number): number | null {
  return answeredCount === 0 ? null : Math.round((correctCount / answeredCount) * 100);
}
