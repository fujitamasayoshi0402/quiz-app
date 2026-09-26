import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * テストの前に、E2E のテナントと所属を DB に用意する（`fixtures.sql`）。
 *
 * docker compose の postgres に psql で流す。**E2E は docker compose で起動した一式にだけ向ける**
 * （ローカルと CI）。dev の DB には、テストの所属を作らない。
 */
export default function globalSetup() {
  const repositoryRoot = path.resolve(__dirname, "../..");
  execFileSync(
    "docker",
    ["compose", "exec", "-T", "postgres", "psql", "-U", "quiz", "-d", "quiz", "-v", "ON_ERROR_STOP=1", "-q"],
    { cwd: repositoryRoot, input: readFileSync(path.join(__dirname, "fixtures.sql")), stdio: ["pipe", "inherit", "inherit"] },
  );
}
