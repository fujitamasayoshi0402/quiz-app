/**
 * つなぐ先（dev / prod）の定義（DEV-208）。ビルドのときに渡した値から作る。React と端末には触れない
 */
export type EnvironmentName = "prod" | "dev";

export type Environment = {
  name: EnvironmentName;
  label: string;
  /** API Gateway。web の proxy は通さない */
  apiOrigin: string;
  authIssuer: string;
  /** Cognito のアプリ用のクライアント（web のクライアントではない） */
  authClientId: string;
};

export type EnvironmentValues = {
  apiOrigin?: string;
  authIssuer?: string;
  authClientId?: string;
};

/** 並べる順。最初のものを、まだ選んでいないときのつなぐ先にする。prod は止まらないので、待たずに使える */
const ORDER: EnvironmentName[] = ["prod", "dev"];

const LABELS: Record<EnvironmentName, string> = { prod: "本番（prod）", dev: "開発（dev）" };

const VARIABLES: Record<keyof EnvironmentValues, string> = {
  apiOrigin: "API_ORIGIN",
  authIssuer: "AUTH_ISSUER",
  authClientId: "AUTH_CLIENT_ID",
};

/**
 * 渡された値から、つなぐ先の一覧を作る。値がすべて空の環境は、つながない（一覧に入れない）。
 *
 * **一部だけ埋まっているときと、どの環境も無いときは落とす。** 空のまま動くと、ログインの画面やすべての API が、原因の分からないエラーになる
 */
export function environmentsFrom(values: Record<EnvironmentName, EnvironmentValues>): Environment[] {
  const environments: Environment[] = [];
  for (const name of ORDER) {
    const given = values[name];
    const keys = Object.keys(VARIABLES) as (keyof EnvironmentValues)[];
    const missing = keys.filter((key) => !given[key]);
    if (missing.length === keys.length) continue;
    if (missing.length > 0) {
      const names = missing.map((key) => `EXPO_PUBLIC_${name.toUpperCase()}_${VARIABLES[key]}`).join("、");
      throw new Error(`${names} が設定されていません。apps/mobile/.env を確かめてください（.env.example）`);
    }
    environments.push({
      name,
      label: LABELS[name],
      apiOrigin: trimSlash(given.apiOrigin!),
      authIssuer: trimSlash(given.authIssuer!),
      authClientId: given.authClientId!,
    });
  }
  if (environments.length === 0) {
    throw new Error("つなぐ先が設定されていません。apps/mobile/.env を作ってください（.env.example）");
  }
  return environments;
}

/** 前に選んだものがあればそれを、無い（そのビルドに含まれない）ときは一覧の最初を使う */
export function pickEnvironment(environments: Environment[], saved: string | null): Environment {
  return environments.find((environment) => environment.name === saved) ?? environments[0];
}

function trimSlash(value: string): string {
  return value.replace(/\/+$/, "");
}
