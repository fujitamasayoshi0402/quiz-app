import { PublishCommand, SNSClient } from "@aws-sdk/client-sns";
import { format } from "./format.mjs";

/**
 * アラームのトピックから受け、日本語にしてメールのトピックへ送る（DEV-214）。
 * 組み立てに失敗しても、元の文で送る。知らせを落とさない
 */
const sns = new SNSClient({});

export async function handler(event) {
  for (const record of event.Records) {
    const message = record.Sns.Message;
    let mail;
    try {
      mail = format(message, process.env.LABEL);
    } catch (error) {
      console.error("通知を日本語にできませんでした。元の文で送ります", error);
      mail = { subject: `【${process.env.LABEL}】AWS からの通知`, body: message };
    }
    await sns.send(new PublishCommand({ TopicArn: process.env.MAIL_TOPIC_ARN, Subject: mail.subject, Message: mail.body }));
  }
}
