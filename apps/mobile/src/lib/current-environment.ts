import * as SecureStore from "expo-secure-store";
import { environments } from "@/lib/config";
import { type Environment, type EnvironmentName, pickEnvironment } from "@/lib/environments";

/**
 * いまのつなぐ先（DEV-208）。画面からも API の fetch からも使うため、React の外に置く（ログインの状態と同じ）。
 *
 * 選んだものは端末に覚える。秘密ではないが、ほかに端末に置く仕組みを入れていない。読めなければ一覧の最初を使う
 */
const STORE_KEY = "environment";

let current: Environment | undefined;
const listeners = new Set<(environment: Environment) => void>();

export async function loadEnvironment(): Promise<Environment> {
  if (!current) {
    let saved: string | null = null;
    try {
      saved = await SecureStore.getItemAsync(STORE_KEY);
    } catch {
      // 読めなければ、一覧の最初につなぐ
    }
    current ??= pickEnvironment(environments, saved);
  }
  return current;
}

/** 読み込んだあと（画面を描いたあと）にだけ呼ぶ。画面は、読み込み終えるまで描かない（`EnvironmentProvider`） */
export function currentEnvironment(): Environment {
  if (!current) throw new Error("つなぐ先をまだ読んでいません");
  return current;
}

export async function selectEnvironment(name: EnvironmentName): Promise<void> {
  const next = environments.find((environment) => environment.name === name);
  if (!next || next === current) return;
  current = next;
  try {
    await SecureStore.setItemAsync(STORE_KEY, name);
  } catch {
    // 覚えられなくても、次に起動したときに一覧の最初につなぐだけ
  }
  listeners.forEach((listener) => listener(next));
}

export function onEnvironmentChange(listener: (environment: Environment) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
