import * as AuthSession from "expo-auth-session";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { useEnvironment } from "@/lib/environment-provider";
import { completeSignIn, loadSession, onSessionChange, redirectUri, scopes, signOut } from "./client";
import type { Session } from "./session";

type Auth = {
  /** loading は、端末からまだ読んでいない */
  status: "loading" | "signedOut" | "signedIn";
  /** ログインの画面を開ける状態か。Cognito の設定を読み終えるまでは開けない */
  ready: boolean;
  error: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<Auth | null>(null);

/**
 * ログインの状態を画面に配る。ログインは、システムのブラウザで Managed Login を開き、PKCE でコードを受ける（ADR-0032）。
 *
 * いまのつなぐ先のログインの状態を配る。つなぐ先が替わったら、作り直す（`key` に名前を渡す。DEV-208）
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const { environment } = useEnvironment();
  const discovery = AuthSession.useAutoDiscovery(environment.authIssuer);
  const [request, , promptAsync] = AuthSession.useAuthRequest(
    { clientId: environment.authClientId, redirectUri, scopes, usePKCE: true },
    discovery,
  );
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadSession(environment).then(setSession);
    return onSessionChange(setSession);
  }, [environment]);

  const signIn = useCallback(async () => {
    if (!request?.codeVerifier) return;
    setError(null);
    const result = await promptAsync();
    if (result.type === "success") {
      try {
        await completeSignIn(environment, result.params.code, request.codeVerifier);
      } catch {
        setError("ログインできませんでした。もう一度お試しください");
      }
    } else if (result.type === "error") {
      setError("ログインできませんでした。もう一度お試しください");
    }
  }, [environment, request, promptAsync]);

  const status = session === undefined ? "loading" : session ? "signedIn" : "signedOut";
  return <AuthContext value={{ status, ready: request !== null, error, signIn, signOut }}>{children}</AuthContext>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error("AuthProvider の外で useAuth を呼んでいます");
  return auth;
}
