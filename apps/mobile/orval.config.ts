import { defineConfig } from "orval";

/**
 * OpenAPI 定義から TanStack Query のフックと Zod スキーマを生成する（ADR-0032）。
 *
 * web と同じ定義から作る。呼び方（接続先、トークン）が web と違うため、生成物は共有せず、アプリの fetch（mutator）で作る。
 * **真実はコード → 定義 → この生成物**の一方向で、ここを手で直さない（ADR-0010）。
 */
export default defineConfig({
  quiz: {
    input: "../../docs/api/openapi.yaml",
    output: {
      mode: "single",
      target: "src/lib/api/generated/endpoints.ts",
      schemas: { type: "zod", path: "src/lib/api/generated/model" },
      client: "react-query",
      httpClient: "fetch",
      clean: true,
      override: {
        mutator: { path: "src/lib/api/fetcher.ts", name: "apiFetch" },
        // 応答を Zod で検証する。検証は mutator が行うため、スキーマを引数で受け取る
        fetch: { runtimeValidation: true, includeHttpResponseReturnType: false },
        includeZodSchemaInArguments: true,
        query: { signal: true },
      },
    },
  },
});
