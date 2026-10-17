import * as SecureStore from "expo-secure-store";
import { type FeedbackMode, parseModes, rememberMode } from "@/lib/play";

/**
 * 方式はサーバーに保存しないため、再開したときに分からなくなる（web の `lib/play/mode.ts`）。
 * この端末で始めた挑戦に限り、端末に覚えておく。web や別の端末で始めた挑戦は、学習モードで再開する。
 *
 * 置き場所は、ログインの状態と同じ `expo-secure-store` にする。秘密ではないが、ほかに端末に置く仕組みを入れていない。
 * 書けなくても、再開が学習モードになるだけなので、失敗は無視する
 */
const STORE_KEY = "play-modes";

export async function saveMode(attemptId: string, mode: FeedbackMode): Promise<void> {
  try {
    const modes = parseModes(await SecureStore.getItemAsync(STORE_KEY));
    await SecureStore.setItemAsync(STORE_KEY, JSON.stringify(rememberMode(modes, attemptId, mode)));
  } catch {
    // 覚えられなくても、学習モードで再開するだけ
  }
}

export async function loadMode(attemptId: string): Promise<FeedbackMode> {
  try {
    return parseModes(await SecureStore.getItemAsync(STORE_KEY))[attemptId] ?? "learn";
  } catch {
    return "learn";
  }
}
