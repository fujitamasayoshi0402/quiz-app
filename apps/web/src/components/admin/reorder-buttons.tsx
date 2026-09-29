"use client";

import { ChevronDown, ChevronUp } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { Direction } from "@/lib/admin/reorder";

/**
 * 項目を 1 つ上か下へ動かすボタン（DEV-71）。ドラッグにしないのは、キーボードと読み上げ、狭い画面のタッチで確実に使えるようにするため。
 * どの項目を動かすかは、ボタンの名前（`aria-label`）で読み上げる。
 */
export function ReorderButtons({
  name,
  canMoveUp,
  canMoveDown,
  disabled = false,
  onMove,
}: {
  name: string;
  canMoveUp: boolean;
  canMoveDown: boolean;
  disabled?: boolean;
  onMove: (direction: Direction) => void;
}) {
  return (
    <div className="flex shrink-0 gap-1">
      <Button
        type="button"
        variant="ghost"
        size="icon"
        aria-label={`「${name}」を上へ`}
        disabled={disabled || !canMoveUp}
        onClick={() => onMove(-1)}
      >
        <ChevronUp />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        aria-label={`「${name}」を下へ`}
        disabled={disabled || !canMoveDown}
        onClick={() => onMove(1)}
      >
        <ChevronDown />
      </Button>
    </div>
  );
}
