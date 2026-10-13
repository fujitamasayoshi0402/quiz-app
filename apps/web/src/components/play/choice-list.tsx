"use client";

import { Check, X } from "lucide-react";
import type { DeliveredChoice } from "@/lib/api/generated/model";
import { cn } from "@/lib/utils";

const LETTERS = ["A", "B", "C", "D"];

/**
 * 4 択。`reveal` を渡すと正解と選んだ選択肢を色と印で示し、選び直せなくする。
 * 学習・模試・テンポの各モードの回答と、結果画面の振り返りで共用する。
 *
 * 見た目はテンポモード（DEV-75）に揃える（DEV-141）。丸いカードと A〜D の記号。
 * 答えると、正解は緑と ✓、選んだ不正解は赤と ×、ほかは薄くする
 */
export function ChoiceList({
  choices,
  selectedId,
  onSelect,
  reveal,
  shortcuts = false,
}: {
  choices: DeliveredChoice[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  reveal?: { correctChoiceId: string };
  /** 1〜4 のキーで選べる画面（テンポモード）で、そのことを支援技術に伝える */
  shortcuts?: boolean;
}) {
  const locked = !onSelect || !!reveal;
  return (
    <ol className="grid gap-3">
      {choices.map((choice, index) => {
        const selected = choice.id === selectedId;
        const correct = reveal?.correctChoiceId === choice.id;
        const wrongPick = !!reveal && selected && !correct;
        return (
          <li key={choice.id}>
            <button
              type="button"
              disabled={locked}
              onClick={() => onSelect?.(choice.id)}
              aria-pressed={selected}
              aria-keyshortcuts={shortcuts ? String(index + 1) : undefined}
              className={cn(
                "flex w-full items-center gap-4 rounded-2xl border border-foreground/10 bg-card px-4 py-3.5 text-left shadow-sm transition-all duration-200",
                !locked && "hover:border-foreground/25 hover:shadow-md active:scale-[0.98] enabled:cursor-pointer",
                selected && !reveal && "border-foreground/40 shadow-md",
                correct && "border-emerald-500/50 bg-emerald-50 ring-4 ring-emerald-500/10 dark:bg-emerald-950/40",
                wrongPick && "border-red-500/50 bg-red-50 ring-4 ring-red-500/10 dark:bg-red-950/40",
                reveal && !correct && !wrongPick && "opacity-45",
              )}
            >
              <span
                className={cn(
                  "flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-sm font-semibold text-muted-foreground transition-colors",
                  selected && !reveal && "bg-foreground text-background",
                  correct && "bg-emerald-600 text-white",
                  wrongPick && "bg-red-600 text-white",
                )}
              >
                {correct ? (
                  <Check className="size-4" aria-label="正解" />
                ) : wrongPick ? (
                  <X className="size-4" aria-label="選んだ選択肢" />
                ) : (
                  LETTERS[index]
                )}
              </span>
              <span className="flex-1 leading-relaxed whitespace-pre-wrap">{choice.body}</span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}
