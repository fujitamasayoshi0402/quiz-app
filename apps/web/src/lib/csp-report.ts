/**
 * CSP の違反の報告（`report-uri` の形、`{"csp-report": {...}}`）から、ログに残す項目だけを取り出す（DEV-123）。
 *
 * **URL はパスまでにし、クエリを落とす。招待の画面のパスにはトークンが入るので伏せる。**
 * 違反した先は、オリジンか、`inline` / `eval` のようなキーワードだけを残す。どの送り先を許せばよいかは、それで分かる
 */
export type Violation = { document: string; directive: string; blocked: string };

export function summarizeViolation(body: string): Violation | undefined {
  const report = parse(body);
  if (!report) return undefined;
  const directive = text(report["effective-directive"]) ?? text(report["violated-directive"]);
  if (!directive) return undefined;
  return {
    document: pathOf(text(report["document-uri"])),
    directive: directive.split(" ")[0],
    blocked: originOf(text(report["blocked-uri"])),
  };
}

function parse(body: string): Record<string, unknown> | undefined {
  try {
    const report = (JSON.parse(body) as { "csp-report"?: unknown })["csp-report"];
    return report && typeof report === "object" ? (report as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined;
}

function pathOf(value: string | undefined): string {
  if (!value) return "";
  try {
    return new URL(value).pathname.replace(/^\/invitations\/[^/]+/, "/invitations/[token]");
  } catch {
    return "";
  }
}

/** URL ならオリジン、そうでなければ（`inline`、`eval`、`data` など）そのまま */
function originOf(value: string | undefined): string {
  if (!value) return "";
  try {
    return new URL(value).origin;
  } catch {
    return value.slice(0, 32);
  }
}
