"use client";

import * as React from "react";
import { cn } from "cn";
import { Dialog as SheetPrimitive } from "radix-ui";
import { XIcon } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * 画面の下からせり上がるシート。Radix の Dialog を下に寄せて使う。上のつまみを引いて、閉じたり広げたりできる。
 *
 * **後ろの画面をぼかさない。** 上に残る問題文や選択肢を見ながら読めるようにする（DEV-75）。
 * 暗くするのも少しだけにとどめる
 */
function Sheet({ ...props }: React.ComponentProps<typeof SheetPrimitive.Root>) {
  return <SheetPrimitive.Root data-slot="sheet" {...props} />;
}

/** 引いて離したときに閉じる距離（px） */
const DISMISS_DISTANCE = 100;
/** 上に引いたときに広げる距離（px） */
const EXPAND_DISTANCE = 40;

function SheetContent({ className, children, ...props }: React.ComponentProps<typeof SheetPrimitive.Content>) {
  const closeRef = React.useRef<HTMLButtonElement>(null);
  const drag = React.useRef<{ startY: number; pointerId: number } | null>(null);
  const [offset, setOffset] = React.useState(0);
  const [expanded, setExpanded] = React.useState(false);
  const [dragging, setDragging] = React.useState(false);

  // 上のつまみを引いて動かす。下に引いて離すと閉じ、上に引くと画面の 9 割まで広げる（長い解説のとき）。
  // 引き出しの部品（vaul）は保守が止まっているため使わず、つまみの上の指の動きだけを追う
  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    drag.current = { startY: event.clientY, pointerId: event.pointerId };
    setDragging(true);
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    if (drag.current?.pointerId !== event.pointerId) return;
    const delta = event.clientY - drag.current.startY;
    if (delta < -EXPAND_DISTANCE) setExpanded(true);
    // 上には動かさない。広げるのは高さの上限で行う（中身より高くはならない）
    setOffset(Math.max(0, delta));
  };
  const onPointerEnd = (event: React.PointerEvent<HTMLDivElement>) => {
    if (drag.current?.pointerId !== event.pointerId) return;
    drag.current = null;
    setDragging(false);
    if (offset > DISMISS_DISTANCE) closeRef.current?.click();
    else setOffset(0);
  };

  return (
    <SheetPrimitive.Portal>
      <SheetPrimitive.Overlay
        data-slot="sheet-overlay"
        className="fixed inset-0 z-50 bg-black/10 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0"
      />
      <SheetPrimitive.Content
        data-slot="sheet-content"
        data-expanded={expanded || undefined}
        style={offset > 0 ? { transform: `translateY(${offset}px)` } : undefined}
        className={cn(
          "fixed inset-x-0 bottom-0 z-50 mx-auto flex max-h-[60dvh] w-full max-w-xl flex-col rounded-t-3xl bg-popover text-popover-foreground shadow-[0_-8px_30px_rgb(0_0_0/0.12)] ring-1 ring-foreground/5 outline-none data-expanded:max-h-[90dvh]",
          "duration-300 data-open:animate-in data-open:slide-in-from-bottom data-closed:animate-out data-closed:slide-out-to-bottom",
          // 引いている間は指に付いてくる。離したら、滑らかに元の位置へ戻る
          dragging ? "transition-[max-height]" : "transition-[max-height,transform]",
          className,
        )}
        {...props}
      >
        <div
          aria-hidden
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerEnd}
          onPointerCancel={onPointerEnd}
          // 指の追跡が途中で切れたとき（通知が出た、など）も、引いたままで止まらないようにする
          onLostPointerCapture={onPointerEnd}
          className="flex h-8 w-full shrink-0 cursor-grab touch-none items-center justify-center active:cursor-grabbing"
        >
          <div className="h-1.5 w-10 rounded-full bg-muted-foreground/25" />
        </div>
        {children}
        <SheetPrimitive.Close asChild>
          <Button ref={closeRef} variant="ghost" size="icon-sm" className="absolute top-3 right-3 rounded-full">
            <XIcon />
            <span className="sr-only">閉じる</span>
          </Button>
        </SheetPrimitive.Close>
      </SheetPrimitive.Content>
    </SheetPrimitive.Portal>
  );
}

function SheetHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="sheet-header" className={cn("px-6 pt-1 pb-2", className)} {...props} />;
}

function SheetTitle({ className, ...props }: React.ComponentProps<typeof SheetPrimitive.Title>) {
  return (
    <SheetPrimitive.Title
      data-slot="sheet-title"
      className={cn("text-base font-semibold tracking-tight", className)}
      {...props}
    />
  );
}

function SheetBody({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-body"
      className={cn(
        "min-h-0 flex-1 overflow-y-auto overscroll-contain px-6 pb-[max(1.5rem,env(safe-area-inset-bottom))]",
        className,
      )}
      {...props}
    />
  );
}

export { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle };
