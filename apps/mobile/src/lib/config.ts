import { environmentsFrom } from "@/lib/environments";

/**
 * ビルドのときに渡すつなぐ先（`.env.example`）。`EXPO_PUBLIC_` の値は、バンドルを作るときに埋め込まれる。
 * dev と prod の片方だけを渡せば、その環境に固定する（DEV-208）
 */
// process.env.EXPO_PUBLIC_* は、名前をそのまま書いたときだけ置き換わる。変数を介して読まない
export const environments = environmentsFrom({
  prod: {
    apiOrigin: process.env.EXPO_PUBLIC_PROD_API_ORIGIN,
    authIssuer: process.env.EXPO_PUBLIC_PROD_AUTH_ISSUER,
    authClientId: process.env.EXPO_PUBLIC_PROD_AUTH_CLIENT_ID,
  },
  dev: {
    apiOrigin: process.env.EXPO_PUBLIC_DEV_API_ORIGIN,
    authIssuer: process.env.EXPO_PUBLIC_DEV_AUTH_ISSUER,
    authClientId: process.env.EXPO_PUBLIC_DEV_AUTH_CLIENT_ID,
  },
});
