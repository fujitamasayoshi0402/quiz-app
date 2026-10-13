import { cn } from "@/lib/utils";

/**
 * 出題まわりの画面で揃えて使う小さな部品（DEV-141）。見た目はテンポモード（DEV-75）に合わせる。
 * 配色は既定のまま、余白・文字・角の丸み・影で整える。
 */

/** 見出しの上に小さく添える文字（「QUESTION 3」など） */
export function Eyebrow({ className, children }: { className?: string; children: React.ReactNode }) {
  return <p className={cn("text-xs font-medium tracking-[0.2em] text-muted-foreground", className)}>{children}</p>;
}

/** 数字を大きく見せるカード（回答の数、正答率など） */
export function Stat({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div className={cn("rounded-2xl border border-foreground/10 bg-card px-4 py-3 shadow-sm", className)}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-2xl font-semibold tracking-tight tabular-nums">{value}</dd>
    </div>
  );
}

/** 細い進み具合の線 */
export function ProgressLine({ value, max, label }: { value: number; max: number; label: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      className="h-1 flex-1 overflow-hidden rounded-full bg-foreground/10"
    >
      <div
        className="h-full rounded-full bg-foreground/70 transition-[width] duration-500 ease-out"
        style={{ width: `${max === 0 ? 0 : (value / max) * 100}%` }}
      />
    </div>
  );
}

/** 丸い角と柔らかい影のカード。画面の中のまとまりに使う */
export function Panel({ className, ...props }: React.ComponentProps<"div">) {
  return <div className={cn("rounded-2xl border border-foreground/10 bg-card p-4 shadow-sm", className)} {...props} />;
}

/** 正誤の文字。色だけに頼らず、文字でも伝える */
export function Verdict({ correct, answered = true }: { correct: boolean; answered?: boolean }) {
  if (!answered) return <span className="text-sm font-semibold text-muted-foreground">未回答</span>;
  return (
    <span
      className={cn(
        "text-sm font-semibold",
        correct ? "text-emerald-700 dark:text-emerald-400" : "text-red-700 dark:text-red-400",
      )}
    >
      {correct ? "正解" : "不正解"}
    </span>
  );
}
