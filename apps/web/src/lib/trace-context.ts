/**
 * API へ渡すトレースの文脈（W3C の `traceparent`。ADR-0026）。web の proxy が要求ごとに作る。
 *
 * web の区間は記録しない。quiz-service がこの trace ID を引き継ぎ、そこから先（DB、イベント、通知）を記録する。
 *
 * **trace ID の先頭 8 桁は、作った時刻（エポック秒）にする。** X-Ray は、先頭が時刻として読めない trace ID を捨てることがある。
 * quiz-service も同じ形で作る（`XrayCompatibleIdGenerator`）。
 * 記録するかの印（末尾の `01`）は付けるが、quiz-service は従わず、自分で決める
 */
export function newTraceParent(now: number = Date.now()): string {
  const epochSeconds = Math.floor(now / 1000)
    .toString(16)
    .padStart(8, "0");
  return `00-${epochSeconds}${randomHex(12)}-${randomHex(8)}-01`;
}

function randomHex(bytes: number): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(bytes)), (b) => b.toString(16).padStart(2, "0")).join("");
}
