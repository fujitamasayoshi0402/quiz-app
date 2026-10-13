"use client";

import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { cn } from "@/lib/utils";

export type Option = { value: string; label: string; hint?: string };

/** ラベル全体を押せるラジオボタンの一覧。出題条件の各項目で使う。見た目は選択肢（`ChoiceList`）に揃える（DEV-141） */
export function OptionGroup({
  name,
  options,
  value,
  onChange,
  columns = 1,
}: {
  name: string;
  options: Option[];
  value: string;
  onChange: (value: string) => void;
  columns?: 1 | 2 | 4;
}) {
  return (
    <RadioGroup
      value={value}
      onValueChange={onChange}
      className={cn("grid gap-3", columns === 2 && "sm:grid-cols-2", columns === 4 && "grid-cols-2 sm:grid-cols-4")}
    >
      {options.map((option) => {
        const id = `${name}-${option.value}`;
        return (
          <Label
            key={option.value}
            htmlFor={id}
            className="flex cursor-pointer items-start gap-3 rounded-2xl border border-foreground/10 bg-card px-4 py-3.5 font-normal shadow-sm transition-all duration-200 hover:border-foreground/25 hover:shadow-md active:scale-[0.99] has-data-[state=checked]:border-foreground/40 has-data-[state=checked]:shadow-md has-data-[state=checked]:ring-4 has-data-[state=checked]:ring-foreground/5"
          >
            <RadioGroupItem value={option.value} id={id} className="mt-0.5" />
            <span className="grid gap-0.5">
              <span className="font-medium">{option.label}</span>
              {option.hint && <span className="text-xs text-muted-foreground">{option.hint}</span>}
            </span>
          </Label>
        );
      })}
    </RadioGroup>
  );
}
