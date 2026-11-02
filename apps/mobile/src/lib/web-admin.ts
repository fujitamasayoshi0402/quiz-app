import type { Environment } from "@/lib/environments";

/**
 * web の管理画面の URL（DEV-215）。アプリには管理の画面を作らず、管理者は web へ移って直す（ADR-0032 の追記）。
 * 管理者でないとき、つなぐ先に web の origin が無いビルドでは、開く先を返さない（ボタンを出さない）
 */
export function webAdminUrl(environment: Environment, slug: string, role: string | undefined): string | undefined {
  if (role !== "admin" || !environment.webOrigin) return undefined;
  return `${environment.webOrigin}/t/${encodeURIComponent(slug)}/admin`;
}
