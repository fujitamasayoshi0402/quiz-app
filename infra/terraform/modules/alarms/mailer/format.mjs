/**
 * アラームのトピックに届いた通知を、日本語の件名と本文にする（DEV-214）。AWS には触れない。
 *
 * 届くのは 3 種類。どれとも読めなければ、元の文をそのまま添えて送る。知らせを落とさない
 * - CloudWatch のアラーム: JSON（AlarmName、AlarmDescription、NewStateReason など）
 * - 予算（AWS Budgets）: 英語の文。「Budget Name: ...」のような行を読む
 * - EventBridge のルール（タスクの停止など）: 日本語の文。最初の文を件名にする
 */

/** SNS の件名は 100 文字未満 */
const SUBJECT_MAX = 90;

/**
 * @param {string} message SNS のメッセージ
 * @param {string} label 件名の頭に付ける、どこからの知らせか（dev、prod、アカウント）
 * @returns {{ subject: string, body: string }}
 */
export function format(message, label) {
  const alarm = parseJson(message);
  if (alarm && typeof alarm.AlarmName === "string") return formatAlarm(alarm, label);
  if (/^Budget Name:/m.test(message)) return formatBudget(message, label);
  return formatText(message, label);
}

function formatAlarm(alarm, label) {
  const description = alarm.AlarmDescription ?? "";
  const title = firstSentence(description) || alarm.AlarmName;
  const region = alarm.AlarmArn?.split(":")[3] ?? "ap-northeast-1";
  const consoleUrl = `https://${region}.console.aws.amazon.com/cloudwatch/home?region=${region}#alarmsV2:alarm/${encodeURIComponent(alarm.AlarmName)}`;
  const lines = [
    `${title}`,
    "",
    `いつ: ${toJst(alarm.StateChangeTime)}`,
    `アラーム: ${alarm.AlarmName}`,
    "",
    description,
    "",
    `アラームの画面: ${consoleUrl}`,
    "",
    "AWS の判定（英語）:",
    alarm.NewStateReason ?? "",
  ];
  return { subject: subject(label, title), body: lines.join("\n") };
}

function formatBudget(message, label) {
  const fields = Object.fromEntries(
    [...message.matchAll(/^([A-Za-z ]+):[ \t]*(.+)$/gm)].map(([, key, value]) => [key.trim(), value.trim()]),
  );
  const type = fields["Alert Type"];
  const amount = dollars(fields[`${type} Amount`]);
  const budgeted = dollars(fields["Budgeted Amount"]);
  const threshold = fields["Alert Threshold"];

  let title;
  if (type === "ACTUAL" && amount) title = `今月の費用が ${amount} ドルになりました`;
  else if (type === "FORECASTED" && amount) title = `今月の費用が、月末に ${amount} ドルになる見込みです`;
  else title = "予算の通知が届きました";

  const lines = [
    title,
    "",
    `予算: ${fields["Budget Name"] ?? "（不明）"}${budgeted ? `（月 ${budgeted} ドル）` : ""}`,
    `種類: ${type === "ACTUAL" ? "実績" : type === "FORECASTED" ? "月末の予測" : (type ?? "（不明）")}`,
    ...(threshold ? [`知らせる基準: ${threshold}`] : []),
    "",
    "費用の内訳は、Cost Explorer で Env のタグごとに見る。",
    "",
    "AWS からの通知（英語）:",
    message,
  ];
  return { subject: subject(label, title), body: lines.join("\n") };
}

function formatText(message, label) {
  const title = firstSentence(message) || "AWS からの通知";
  return { subject: subject(label, title), body: message };
}

function subject(label, title) {
  const text = `【${label}】${title}`.replace(/[\r\n]+/g, " ");
  return text.length > SUBJECT_MAX ? `${text.slice(0, SUBJECT_MAX - 1)}…` : text;
}

/** 最初の「。」まで。無ければ最初の行 */
function firstSentence(text) {
  const line = text.trim().split("\n")[0] ?? "";
  const end = line.indexOf("。");
  return end === -1 ? line : line.slice(0, end + 1);
}

/** CloudWatch の時刻（`2026-10-31T01:23:45.678+0000`）を、日本時間にする。読めなければそのまま */
function toJst(time) {
  const date = new Date(String(time).replace(/([+-]\d{2})(\d{2})$/, "$1:$2"));
  if (Number.isNaN(date.getTime())) return String(time);
  const jst = new Date(date.getTime() + 9 * 60 * 60_000).toISOString();
  return `${jst.slice(0, 10)} ${jst.slice(11, 16)}（日本時間）`;
}

function dollars(value) {
  return value?.match(/\$?\s*([\d,]+(?:\.\d+)?)/)?.[1];
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
