import { NextResponse, type NextRequest } from "next/server";
import { summarizeViolation } from "@/lib/csp-report";

/**
 * CSP の違反の報告を受け、サーバーのログに 1 行で残す（DEV-123）。
 * dev では Amplify の SSR のログ（CloudWatch Logs）に出る。強制に切り替える前に、取りこぼした送り先がないかをここで見る。
 *
 * 誰でも送れるため、大きな本文は読まず、残すのは決まった項目だけにする（`lib/csp-report.ts`）
 */
export async function POST(request: NextRequest) {
  const body = await request.text();
  if (body.length <= MAX_BODY_LENGTH) {
    const violation = summarizeViolation(body);
    if (violation) console.warn("CSP の違反", JSON.stringify(violation));
  }
  return new NextResponse(null, { status: 204 });
}

const MAX_BODY_LENGTH = 16 * 1024;
