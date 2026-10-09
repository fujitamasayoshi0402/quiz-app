/** テナントでの役割の表示名。値はバックエンドの `core.tenant_members.role` */
export function roleLabel(role: string) {
  return role === "admin" ? "管理者" : "一般ユーザー";
}
