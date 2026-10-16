import * as AuthSession from "expo-auth-session";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { config } from "@/lib/config";
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
 * ログインの状態を画面に配る。ログインは、システムのブラウザで Managed Login を開き、PKCE でコードを受ける（ADR-0032）
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const discovery = AuthSession.useAutoDiscovery(config.authIssuer);
  const [request, , promptAsync] = AuthSession.useAuthRequest(
    { clientId: config.authClientId, redirectUri, scopes, usePKCE: true },
    discovery,
  );
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadSession().then(setSession);
    return onSessionChange(setSession);
  }, []);

  const signIn = useCallback(async () => {
    if (!request?.codeVerifier) return;
    setError(null);
    const result = await promptAsync();
    if (result.type === "success") {
      try {
        await completeSignIn(result.params.code, request.codeVerifier);
      } catch {
        setError("ログインできませんでした。もう一度お試しください");
      }
    } else if (result.type === "error") {
      setError("ログインできませんでした。もう一度お試しください");
    }
  }, [request, promptAsync]);

  const status = session === undefined ? "loading" : session ? "signedIn" : "signedOut";
  return <AuthContext value={{ status, ready: request !== null, error, signIn, signOut }}>{children}</AuthContext>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error("AuthProvider の外で useAuth を呼んでいます");
  return auth;
}
