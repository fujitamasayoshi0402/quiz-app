import { signOut } from "@/app/actions";
import { Button } from "@/components/ui/button";

export function SignOutButton({ className }: { className?: string }) {
  return (
    <form action={signOut} className={className}>
      <Button type="submit" variant="link" size="sm" className="h-auto px-0 py-2">
        利用者を切り替える
      </Button>
    </form>
  );
}
