import { describe, expect, it } from "vitest";
import { isAuthRedirect } from "./redirect";

describe("isAuthRedirect", () => {
  it.each([
    "quizapp://auth/callback?code=abc&state=xyz",
    "quizapp://auth/logout",
    "exp://127.0.0.1:8081/--/auth/callback?code=abc",
    "/auth/callback?code=abc",
    "auth/logout",
  ])("%s は戻り先", (path) => {
    expect(isAuthRedirect(path)).toBe(true);
  });

  it.each(["quizapp://t/demo", "/t/demo/history", "/", "quizapp://auth/callbacks", "/t/auth"])(
    "%s は戻り先ではない",
    (path) => {
      expect(isAuthRedirect(path)).toBe(false);
    },
  );
});
