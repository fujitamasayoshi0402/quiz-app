import { describe, expect, it } from "vitest";
import { canOpenAdmin, roleLabel } from "@/lib/auth/roles";

describe("roles", () => {
  it("見学者は管理画面を開ける。一般ユーザーは開けない", () => {
    expect(canOpenAdmin("admin")).toBe(true);
    expect(canOpenAdmin("viewer")).toBe(true);
    expect(canOpenAdmin("member")).toBe(false);
  });

  it("見学者の表示名", () => {
    expect(roleLabel("viewer")).toBe("見学者");
  });
});
