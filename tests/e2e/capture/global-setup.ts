import { readFileSync } from "node:fs";
import path from "node:path";
import { runSql } from "../support/database";

/**
 * E2E と同じ所属（`fixtures.sql`）を用意してから、撮るためのデータ（`capture.sql`）を入れる。
 * どちらも何度流しても同じ状態になる
 */
export default function globalSetup() {
  runSql(readFileSync(path.join(__dirname, "..", "fixtures.sql")));
  runSql(readFileSync(path.join(__dirname, "capture.sql")));
}
