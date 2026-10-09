import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier/flat";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  // 見た目に関わるルールを切る。整形は Prettier が受け持ち、両方が別々の形を求めないようにする（DEV-78）。
  // 後ろの設定が前を上書きするため、最後に置く
  prettier,
]);

export default eslintConfig;
