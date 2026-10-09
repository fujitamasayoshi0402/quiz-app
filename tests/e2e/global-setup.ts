import { readFileSync } from "node:fs";
import path from "node:path";
import { runSql } from "./support/database";

/**
 * テストの前に、E2E のテナントと所属を DB に用意する（`fixtures.sql`）。
 *
 * docker compose の postgres に psql で流す。**E2E は docker compose で起動した一式にだけ向ける**
 * （ローカルと CI）。dev の DB には、テストの所属を作らない。
 */
export default function globalSetup() {
  runSql(readFileSync(path.join(__dirname, "fixtures.sql")));
}
