import { Badge } from "@/components/ui/badge";

export function StatusBadge({ status }: { status: string }) {
  return status === "published" ? <Badge>公開</Badge> : <Badge variant="secondary">下書き</Badge>;
}
