import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { environments } from "@/lib/config";
import { loadEnvironment, onEnvironmentChange, selectEnvironment } from "@/lib/current-environment";
import type { Environment, EnvironmentName } from "@/lib/environments";

type EnvironmentState = {
  environment: Environment;
  /** このビルドでつなげる先。1 つなら、切り替えを出さない */
  environments: Environment[];
  select: (name: EnvironmentName) => Promise<void>;
};

const EnvironmentContext = createContext<EnvironmentState | null>(null);

/**
 * いまのつなぐ先を画面に配る（DEV-208）。端末から読み終えるまでは何も描かない。読むのは一瞬で、
 * ログインの状態も API も、つなぐ先が決まらないと始められない
 */
export function EnvironmentProvider({ children }: { children: ReactNode }) {
  const [environment, setEnvironment] = useState<Environment | null>(null);

  useEffect(() => {
    void loadEnvironment().then(setEnvironment);
    return onEnvironmentChange(setEnvironment);
  }, []);

  if (!environment) return null;
  return (
    <EnvironmentContext value={{ environment, environments, select: selectEnvironment }}>{children}</EnvironmentContext>
  );
}

export function useEnvironment(): EnvironmentState {
  const state = useContext(EnvironmentContext);
  if (!state) throw new Error("EnvironmentProvider の外で useEnvironment を呼んでいます");
  return state;
}
