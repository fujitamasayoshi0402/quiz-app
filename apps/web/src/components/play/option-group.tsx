"use client";

import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { cn } from "@/lib/utils";

export type Option = { value: string; label: string; hint?: string };

/** ラベル全体を押せるラジオボタンの一覧。出題条件の各項目で使う。 */
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
      className={cn("grid gap-2", columns === 2 && "sm:grid-cols-2", columns === 4 && "grid-cols-2 sm:grid-cols-4")}
    >
      {options.map((option) => {
        const id = `${name}-${option.value}`;
        return (
          <Label
            key={option.value}
            htmlFor={id}
            className="has-data-[state=checked]:border-primary has-data-[state=checked]:bg-primary/5 bg-background flex cursor-pointer items-start gap-3 rounded-lg border p-3 font-normal"
          >
            <RadioGroupItem value={option.value} id={id} className="mt-0.5" />
            <span className="grid gap-0.5">
              <span className="font-medium">{option.label}</span>
              {option.hint && <span className="text-muted-foreground text-xs">{option.hint}</span>}
            </span>
          </Label>
        );
      })}
    </RadioGroup>
  );
}
