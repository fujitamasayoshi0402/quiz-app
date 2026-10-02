import { describe, expect, it } from "vitest";
import { summarizeViolation } from "./csp-report";

function report(fields: Record<string, unknown>) {
  return JSON.stringify({ "csp-report": fields });
}

describe("summarizeViolation", () => {
  it("違反した指示と、送り先のオリジンだけを残す", () => {
    const violation = summarizeViolation(
      report({
        "document-uri": "https://dev.example.com/t/demo/admin/quizzes/1?tab=figure",
        "effective-directive": "img-src",
        "violated-directive": "img-src 'self' data: blob:",
        "blocked-uri": "https://figures.dev.example.com/img/a/b.png?Signature=secret",
      }),
    );
    expect(violation).toEqual({
      document: "/t/demo/admin/quizzes/1",
      directive: "img-src",
      blocked: "https://figures.dev.example.com",
    });
  });

  it("招待の画面のトークンを伏せる", () => {
    const violation = summarizeViolation(
      report({
        "document-uri": "https://dev.example.com/invitations/secret-token",
        "violated-directive": "script-src 'self'",
        "blocked-uri": "inline",
      }),
    );
    expect(violation).toEqual({ document: "/invitations/[token]", directive: "script-src", blocked: "inline" });
  });

  it("読めない本文は捨てる", () => {
    expect(summarizeViolation("not json")).toBeUndefined();
    expect(summarizeViolation(JSON.stringify({ other: {} }))).toBeUndefined();
    expect(summarizeViolation(report({ "blocked-uri": "inline" }))).toBeUndefined();
  });
});
