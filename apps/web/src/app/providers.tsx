"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError } from "@/lib/api/fetcher";
import "@/lib/zod-locale";

/**
 * 4xx は再試行しない。入力や権限の問題で、繰り返しても結果が変わらないため。
 * 5xx と通信エラーだけを 2 回まで再試行する。
 */
function shouldRetry(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.status < 500) return false;
  return failureCount < 2;
}

export function Providers({ children }: { children: React.ReactNode }) {
  // コンポーネントの外で作ると、サーバーでリクエストをまたいでキャッシュが共有される
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: shouldRetry, refetchOnWindowFocus: false },
          mutations: { retry: false },
        },
      }),
  );
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
