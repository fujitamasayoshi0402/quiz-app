import type { FullConfig } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { runSql } from "./support/database";
import { loadDemoPack } from "./support/demo-pack";

/**
 * テストの前に、E2E のテナントと所属を DB に用意し（`fixtures.sql`）、デモのテナントにデモのパックを流す。
 *
 * docker compose の postgres に psql で流す。**E2E は docker compose で起動した一式にだけ向ける**
 * （ローカルと CI）。dev の DB には、テストの所属を作らない。
 */
export default async function globalSetup(config: FullConfig) {
  runSql(readFileSync(path.join(__dirname, "fixtures.sql")));
  await loadDemoPack(baseURLOf(config));
}

export function baseURLOf(config: FullConfig): string {
  return config.projects[0]?.use.baseURL ?? "http://localhost:3000";
}
