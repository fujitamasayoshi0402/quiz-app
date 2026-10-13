import { z } from "zod";
import { CreateTenantRequest } from "@/lib/api/generated/model";

/**
 * テナントの作成のフォーム（ADR-0028）。生成したスキーマに「空白だけの名前を拒む」と、slug の分かる理由を足す。
 * 定義の名前の最短文字数は 0 で、`@NotBlank` の制約は OpenAPI に表れないため。slug の正規表現は定義のものをそのまま使う
 */
export const TenantFormSchema = CreateTenantRequest.extend({
  name: CreateTenantRequest.shape.name.trim().min(1, "テナントの名前を入力してください"),
  slug: z
    .string()
    .trim()
    .regex(
      /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/,
      "英小文字・数字・ハイフンで 3〜32 文字にしてください（先頭と末尾はハイフン以外）",
    ),
});
export type TenantFormInput = z.input<typeof TenantFormSchema>;
export type TenantFormValues = z.output<typeof TenantFormSchema>;

/** 作れない理由（`GET /api/me/tenant-creation` の `reason`）ごとの案内 */
export const CREATION_BLOCKS: Record<string, { title: string; description: string }> = {
  shared_account: {
    title: "共有のアカウントでは、テナントを作れません",
    description:
      "このアカウントは、見に来た人が共有で使うものです。自分のテナントで管理の画面を試すには、ログアウトしてから、自分のメールアドレスでアカウントを作ってください。",
  },
  already_created: {
    title: "テナントはすでに作ってあります",
    description: "テナントは 1 人 1 つまで作れます。作ったテナントは、所属しているテナントの一覧から開けます。",
  },
};
