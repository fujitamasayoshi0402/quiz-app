/** テナントでの役割の表示名。値はバックエンドの `core.tenant_members.role` */
export function roleLabel(role: string) {
  if (role === "admin") return "管理者";
  if (role === "viewer") return "見学者";
  return "一般ユーザー";
}

/**
 * 管理画面を開けるか。見学者（ADR-0035）は、読むだけで開ける。
 *
 * **表示の都合で、権限の判定ではない。** 何を読めて何を変えられるかは、バックエンドがパスとロールで決める
 */
export function canOpenAdmin(role: string) {
  return role === "admin" || role === "viewer";
}
