import { NextResponse } from "next/server";
import { demoCredentials } from "@/lib/auth/demo";
import { demoWarmUp } from "@/lib/auth/demo-warm-up";

/**
 * デモに入る前に、止まっている DB を起こし始める（DEV-195。`lib/auth/demo-warm-up.ts`）。「デモとして入る」の入口が、表示したときに送る。
 *
 * - POST だけを受ける。ほかのオリジンからの要求は、web の proxy が止める（`isSameOriginRequest`）
 * - 何も返さない。起こせたかどうかは、見た人には関わらない
 */
export async function POST() {
  if (!demoCredentials()) return new NextResponse(null, { status: 404 });
  await demoWarmUp();
  return new NextResponse(null, { status: 204 });
}
