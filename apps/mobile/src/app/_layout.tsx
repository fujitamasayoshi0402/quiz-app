import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import * as WebBrowser from "expo-web-browser";
import { useState } from "react";
import { ApiError } from "@/lib/api/fetcher";
import { AuthProvider, useAuth } from "@/lib/auth/auth-provider";

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

/** ログインの状態が変わると、取ったものを捨てる。残すと、前の利用者の応答が次の利用者の画面に出る（web と同じ） */
function SignedInQueries({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  return <Queries key={status}>{children}</Queries>;
}

export default function RootLayout() {
  return (
    <AuthProvider>
      <SignedInQueries>
        <StatusBar style="dark" />
        <Stack screenOptions={{ headerBackTitle: "戻る" }}>
          <Stack.Screen name="index" options={{ title: "Quiz" }} />
          <Stack.Screen name="t/[slug]/index" options={{ title: "" }} />
          <Stack.Screen name="t/[slug]/play/index" options={{ title: "クイズを選ぶ" }} />
          <Stack.Screen name="t/[slug]/play/session" options={{ title: "" }} />
          {/* 結果から回答の画面へは戻らせない。戻ると、終わった挑戦の画面が出る */}
          <Stack.Screen
            name="t/[slug]/play/result"
            options={{ title: "結果", headerBackVisible: false, gestureEnabled: false }}
          />
        </Stack>
      </SignedInQueries>
    </AuthProvider>
  );
}
