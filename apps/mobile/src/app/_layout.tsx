import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import * as WebBrowser from "expo-web-browser";
import { useState } from "react";
import { Text } from "react-native";
import { styles } from "@/components/ui";
import { ApiError } from "@/lib/api/fetcher";
import { AuthProvider, useAuth } from "@/lib/auth/auth-provider";
import { EnvironmentProvider, useEnvironment } from "@/lib/environment-provider";

// ログインの画面から戻ったときに、開いていたブラウザの画面を閉じる
WebBrowser.maybeCompleteAuthSession();

/** 4xx は再試行しない。5xx と通信エラーだけを 2 回まで再試行する（web と同じ） */
function shouldRetry(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.status < 500) return false;
  return failureCount < 2;
}

function Queries({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: shouldRetry }, mutations: { retry: false } } }),
  );
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

/**
 * ログインの状態か、つなぐ先が変わると、取ったものを捨てる。残すと、前の利用者の応答が次の利用者の画面に出る（web と同じ）。
 * つなぐ先が替わると、同じ URL の応答でも別の環境のものになる（DEV-208）
 */
function SignedInQueries({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  const { environment } = useEnvironment();
  return <Queries key={`${environment.name}:${status}`}>{children}</Queries>;
}

export default function RootLayout() {
  return (
    <EnvironmentProvider>
      <EnvironmentScoped />
    </EnvironmentProvider>
  );
}

/** つなぐ先が替わったら、ログインの状態から作り直す */
function EnvironmentScoped() {
  const { environment } = useEnvironment();
  return (
    <AuthProvider key={environment.name}>
      <SignedInQueries>
        <StatusBar style="dark" />
        <Screens />
      </SignedInQueries>
    </AuthProvider>
  );
}

/**
 * テナントの画面は、ログインしているときだけ開ける（DEV-210）。ログインしていない（401 で捨てた、つなぐ先を替えた）と、
 * 開いていた画面を閉じて最初の画面に戻る。残すと、トークンを付けずに API を呼び続け、エラーだけが並ぶ。
 * つなげる先が 2 つあれば、いまのつなぐ先をヘッダーの右に出す
 */
function Screens() {
  const { status } = useAuth();
  const { environment, environments } = useEnvironment();
  const headerRight = environments.length > 1 ? () => <Text style={styles.muted}>{environment.name}</Text> : undefined;
  return (
    <Stack screenOptions={{ headerBackTitle: "戻る", headerRight }}>
      <Stack.Screen name="index" options={{ title: "Quiz" }} />
      <Stack.Protected guard={status === "signedIn"}>
        <Stack.Screen name="t/[slug]/index" options={{ title: "" }} />
        <Stack.Screen name="t/[slug]/history" options={{ title: "履歴" }} />
        <Stack.Screen name="t/[slug]/play/index" options={{ title: "クイズを選ぶ" }} />
        <Stack.Screen name="t/[slug]/play/session" options={{ title: "" }} />
        {/* 結果から回答の画面へは戻らせない。戻ると、終わった挑戦の画面が出る */}
        <Stack.Screen
          name="t/[slug]/play/result"
          options={{ title: "結果", headerBackVisible: false, gestureEnabled: false }}
        />
      </Stack.Protected>
    </Stack>
  );
}
