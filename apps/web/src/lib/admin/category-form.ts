import { z } from "zod";
import {
  CreateCategoryRequest,
  SaveDifficultyRequest,
  UpdateCategoryRequest,
} from "@/lib/api/generated/model";

/**
 * カテゴリと難易度のフォーム。生成したスキーマに「空白だけの名前を拒む」を足す。
 * 定義の最短文字数は 0 で、`@NotBlank` の制約は OpenAPI に表れないため。
 *
 * 数値は入力欄から文字列で来るので、`z.coerce` で数値にしてから生成したスキーマに渡す。
 */
const name = (schema: z.ZodString, label: string) => schema.trim().min(1, `${label}を入力してください`);

export const CategoryFormSchema = UpdateCategoryRequest.extend({
  name: name(UpdateCategoryRequest.shape.name, "カテゴリ名"),
  description: z.string(),
  sortOrder: z.coerce.number().int(),
});
export type CategoryFormInput = z.input<typeof CategoryFormSchema>;
export type CategoryFormValues = z.output<typeof CategoryFormSchema>;

export const DifficultyFormSchema = SaveDifficultyRequest.extend({
  name: name(SaveDifficultyRequest.shape.name, "難易度名"),
  level: z.coerce.number().pipe(SaveDifficultyRequest.shape.level),
  description: z.string(),
  sortOrder: z.coerce.number().int(),
});
export type DifficultyFormInput = z.input<typeof DifficultyFormSchema>;
export type DifficultyFormValues = z.output<typeof DifficultyFormSchema>;

/**
 * カテゴリの作成。**最初の難易度も一緒に入力させる**（要件定義「初期状態の扱い」）。
 * 難易度のないカテゴリにはクイズを作れないため、「カテゴリはあるがクイズを作れない」状態を避ける。
 */
export const NewCategoryFormSchema = z.object({
  name: name(CreateCategoryRequest.shape.name, "カテゴリ名"),
  description: z.string(),
  difficultyName: name(SaveDifficultyRequest.shape.name, "難易度名"),
  difficultyLevel: z.coerce.number().pipe(SaveDifficultyRequest.shape.level),
});
export type NewCategoryFormInput = z.input<typeof NewCategoryFormSchema>;
export type NewCategoryFormValues = z.output<typeof NewCategoryFormSchema>;

/** 空の説明は null で送る。空文字列を保存すると「説明あり（空）」と区別できなくなる */
export const blankToNull = (value: string) => (value.trim() === "" ? null : value.trim());
