import { defineConfig, globalIgnores } from "eslint/config";
import expo from "eslint-config-expo/flat.js";
import prettier from "eslint-config-prettier/flat";

export default defineConfig([
  ...expo,
  globalIgnores([".expo/**", "dist/**", "src/lib/api/generated/**"]),
  // 見た目に関わるルールを切る。整形は Prettier が受け持つ（web と同じ。DEV-78）
  prettier,
]);
