import { defineConfig } from "orval";

/**
 * OpenAPI 定義から TanStack Query のフックと Zod スキーマを生成する。
 *
 * 定義はバックエンドのコードから生成して固定したもの（ADR-0010）。
 * **真実はコード → 定義 → この生成物**の一方向で、ここを手で直さない。
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
        // 応答を Zod で検証する。定義とずれた応答は、画面で壊れる前にここで落とす。
        // 独自の fetch（mutator）を使う場合、検証は生成コードではなく mutator が行う。
        // そのためにスキーマを引数で受け取る
        fetch: { runtimeValidation: true, includeHttpResponseReturnType: false },
        includeZodSchemaInArguments: true,
        query: { signal: true },
      },
    },
  },
});
