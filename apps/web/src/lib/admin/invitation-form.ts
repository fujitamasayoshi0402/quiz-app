import { z } from "zod";
import { CreateInvitationRequest } from "@/lib/api/generated/model";

/**
 * 招待のフォーム。生成したスキーマに「前後の空白を除く」と「ロールは 2 つのどれか」を足す。
 * 定義のロールは正規表現（`admin|member`）で、選択肢としては表れないため。
 */
export const InvitationFormSchema = CreateInvitationRequest.extend({
  email: z.string().trim().min(1, "メールアドレスを入力してください").pipe(CreateInvitationRequest.shape.email),
  role: z.enum(["member", "admin"]),
});
export type InvitationFormInput = z.input<typeof InvitationFormSchema>;
export type InvitationFormValues = z.output<typeof InvitationFormSchema>;

/** 招待のリンク。バックエンドは web のオリジンを知らないため、画面で組み立てる */
export const invitationUrl = (token: string) => `${window.location.origin}/invitations/${token}`;
