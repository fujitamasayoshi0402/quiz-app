import { z } from "zod";
import { configureSlackWebhookRequestUrlMax, configureSlackWebhookRequestUrlRegExp } from "@/lib/api/generated/model";

/**
 * Slack の通知先のフォーム。定義の制約（文字数と、`https://hooks.slack.com/` の下を指すこと）に、
 * 「前後の空白を除く」と日本語の案内を足す。生成したスキーマのままだと、正規表現に合わないときの案内が英語になる。
 */
export const SlackWebhookFormSchema = z.object({
  url: z
    .string()
    .trim()
    .min(1, "Webhook の URL を入力してください")
    .max(configureSlackWebhookRequestUrlMax, `URL は ${configureSlackWebhookRequestUrlMax} 文字以内で入力してください`)
    .regex(
      configureSlackWebhookRequestUrlRegExp,
      "Slack の Incoming Webhook の URL（https://hooks.slack.com/ で始まるもの）を入力してください",
    ),
});
export type SlackWebhookFormInput = z.input<typeof SlackWebhookFormSchema>;
export type SlackWebhookFormValues = z.output<typeof SlackWebhookFormSchema>;
