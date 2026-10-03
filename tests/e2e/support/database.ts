import { execFileSync } from "node:child_process";
import path from "node:path";

/**
 * docker compose の postgres に SQL を流す。**E2E は docker compose で起動した一式にだけ向ける**（ローカルと CI）。
 * dev の DB には触れない
 */
export function runSql(sql: string | Buffer) {
  const repositoryRoot = path.resolve(__dirname, "../../..");
  execFileSync(
    "docker",
    ["compose", "exec", "-T", "postgres", "psql", "-U", "quiz", "-d", "quiz", "-v", "ON_ERROR_STOP=1", "-q"],
    { cwd: repositoryRoot, input: sql, stdio: ["pipe", "inherit", "inherit"] },
  );
}
