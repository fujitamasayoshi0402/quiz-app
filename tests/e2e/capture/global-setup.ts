import type { FullConfig } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { baseURLOf } from "../global-setup";
import { runSql } from "../support/database";
import { loadDemoPack } from "../support/demo-pack";

/**
 * E2E と同じ所属（`fixtures.sql`）とデモのパックを用意してから、撮るためのデータ（`capture.sql`）を入れる。
 * どれも何度流しても同じ状態になる
 */
export default async function globalSetup(config: FullConfig) {
  runSql(readFileSync(path.join(__dirname, "..", "fixtures.sql")));
  await loadDemoPack(baseURLOf(config));
  runSql(readFileSync(path.join(__dirname, "capture.sql")));
}
