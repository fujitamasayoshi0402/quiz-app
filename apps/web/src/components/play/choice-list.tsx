"use client";

import { Check, X } from "lucide-react";
import type { DeliveredChoice } from "@/lib/api/generated/model";
import { cn } from "@/lib/utils";

/**
 * 4 択。`reveal` を渡すと正解と選んだ選択肢を色分けして、選び直せなくする。
 * 学習モードの答え合わせと、結果画面の振り返りで共用する。
 */
export function ChoiceList({
  choices,
  selectedId,
  onSelect,
  reveal,
}: {
  choices: DeliveredChoice[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  reveal?: { correctChoiceId: string };
}) {
  return (
    <ol className="grid gap-2">
      {choices.map((choice, index) => {
        const isSelected = choice.id === selectedId;
        const isCorrect = reveal?.correctChoiceId === choice.id;
        const isWrongPick = reveal && isSelected && !isCorrect;
        return (
          <li key={choice.id}>
            <button
              type="button"
              disabled={!onSelect || !!reveal}
              onClick={() => onSelect?.(choice.id)}
              aria-pressed={isSelected}
              className={cn(
                "bg-background flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-colors",
                !reveal && "hover:bg-muted enabled:cursor-pointer",
                !reveal && isSelected && "border-primary bg-primary/5",
                isCorrect && "border-emerald-600 bg-emerald-50 dark:bg-emerald-950",
                isWrongPick && "border-red-600 bg-red-50 dark:bg-red-950",
              )}
            >
              <span className="text-muted-foreground w-5 shrink-0 font-mono text-sm">{index + 1}.</span>
              <span className="flex-1 whitespace-pre-wrap">{choice.body}</span>
              {isCorrect && <Check className="size-5 shrink-0 text-emerald-600" aria-label="正解" />}
              {isWrongPick && <X className="size-5 shrink-0 text-red-600" aria-label="選んだ選択肢" />}
            </button>
          </li>
        );
      })}
    </ol>
  );
}
