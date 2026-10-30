import assert from "node:assert/strict";
import { test } from "node:test";
import { format } from "./format.mjs";

// SNS が届ける CloudWatch のアラームの形（必要な項目だけ）
const alarm = {
  AlarmName: "quiz-app-dev-quiz-service-server-errors",
  AlarmDescription:
    "quiz-service が 5xx を返しました。Logs Insights で http.response.status_code >= 500 の行を見る。手順: https://example.com/runbook",
  AlarmArn: "arn:aws:cloudwatch:ap-northeast-1:123456789012:alarm:quiz-app-dev-quiz-service-server-errors",
  NewStateValue: "ALARM",
  NewStateReason: "Threshold Crossed: 1 datapoint [1.0 (31/10/26 01:20:00)] was greater than or equal to the threshold (1.0).",
  StateChangeTime: "2026-10-31T01:23:45.678+0000",
};

test("アラームは、説明の最初の文を件名にし、時刻を日本時間で出す", () => {
  const { subject, body } = format(JSON.stringify(alarm), "dev");
  assert.equal(subject, "【dev】quiz-service が 5xx を返しました。");
  assert.match(body, /いつ: 2026-10-31 10:23（日本時間）/);
  assert.match(body, /手順: https:\/\/example\.com\/runbook/);
  assert.match(body, /#alarmsV2:alarm\/quiz-app-dev-quiz-service-server-errors/);
  // 英語の判定は最後に添える
  assert.ok(body.trimEnd().endsWith(alarm.NewStateReason));
});

test("説明のないアラームは、名前を件名にする", () => {
  const { subject } = format(JSON.stringify({ ...alarm, AlarmDescription: undefined }), "prod");
  assert.equal(subject, "【prod】quiz-app-dev-quiz-service-server-errors");
});

// AWS Budgets が SNS に送る文の形
const budget = (type, amount) => `AWS Budget Notification October 31, 2026
AWS Account 123456789012

Dear AWS Customer,

You requested that we alert you when the ${type} Cost associated with your 月次コスト予算アラート budget is greater than $25.50 for the current month.

Budget Name: 月次コスト予算アラート
Budget Type: Cost
Budgeted Amount: $30.00
Alert Type: ${type}
Alert Threshold: > $25.50
${type} Amount: $${amount}
`;

test("予算の実績は、いくらになったかを件名にする", () => {
  const { subject, body } = format(budget("ACTUAL", "26.12"), "アカウント");
  assert.equal(subject, "【アカウント】今月の費用が 26.12 ドルになりました");
  assert.match(body, /予算: 月次コスト予算アラート（月 30\.00 ドル）/);
  assert.match(body, /種類: 実績/);
  assert.match(body, /知らせる基準: > \$25\.50/);
  // 元の英語の文も添える
  assert.match(body, /Dear AWS Customer/);
});

test("予算の予測は、月末の見込みを件名にする", () => {
  const { subject, body } = format(budget("FORECASTED", "1,031.50"), "アカウント");
  assert.equal(subject, "【アカウント】今月の費用が、月末に 1,031.50 ドルになる見込みです");
  assert.match(body, /種類: 月末の予測/);
});

test("予算の文が読めなくても、知らせは送る", () => {
  const { subject, body } = format("Budget Name: x\nsomething else", "アカウント");
  assert.equal(subject, "【アカウント】予算の通知が届きました");
  assert.match(body, /something else/);
});

test("EventBridge のルールの文は、最初の文を件名にし、本文はそのまま", () => {
  const text = "quiz-service のタスクが止まりました。時刻: 2026-10-31T01:00:00Z。理由: Essential container exited";
  const { subject, body } = format(text, "prod");
  assert.equal(subject, "【prod】quiz-service のタスクが止まりました。");
  assert.equal(body, text);
});

test("件名は改行を含まず、長ければ切る", () => {
  const { subject } = format(`${"あ".repeat(200)}\n二行目`, "dev");
  assert.ok(subject.length <= 90);
  assert.ok(!subject.includes("\n"));
});
