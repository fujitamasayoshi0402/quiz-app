/**
 * 解説の本文から解説図を指す書き方（ADR-0020）。
 *
 * `![代替テキスト](figure:<図の ID>)` は本文の中に図を出し、`[文字](figure:<図の ID>)` は図を新しいタブで開く。
 * 保存するときに、指している図がテナントにあるかをバックエンドが確かめる（`FigureReferences`）。
 * 拾い方はバックエンドと揃える。`figure:` のあとが UUID の形でなければ、図として扱わない。
 */
const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const REFERENCE = new RegExp(`^figure:(${UUID})$`, "i");
const REFERENCES_IN_TEXT = new RegExp(`figure:(${UUID})(?![0-9a-f-])`, "gi");

/** URL が図を指していれば、その ID（小文字）。指していなければ undefined */
export function figureIdOf(url: string | undefined): string | undefined {
  return url?.match(REFERENCE)?.[1].toLowerCase();
}

/** 本文が指している図の ID。出てきた順に、重ねずに返す */
export function figureIdsIn(text: string): string[] {
  return [...new Set(Array.from(text.matchAll(REFERENCES_IN_TEXT), (match) => match[1].toLowerCase()))];
}

/** 図を取りに行く URL。API が所属を確かめ、期限つきの署名付き URL へ送る（ADR-0017） */
export function figureUrl(tenant: string, id: string): string {
  return `/api/t/${encodeURIComponent(tenant)}/play/figures/${id}`;
}

/** 本文に入れる、図を出す書き方 */
export function figureMarkdown(id: string, alt = "図"): string {
  return `![${alt}](figure:${id})`;
}

/** 本文の [position] の位置に、図を出す書き方を 1 行として入れる。位置が分からなければ末尾に入れる */
export function insertFigure(text: string, position: number | null, id: string, alt = "図"): string {
  const at = position ?? text.length;
  const before = text.slice(0, at);
  const after = text.slice(at);
  const head = before && !before.endsWith("\n") ? "\n" : "";
  const tail = after && !after.startsWith("\n") ? "\n" : "";
  return `${before}${head}${figureMarkdown(id, alt)}${tail}${after}`;
}

/** 本文の中で、ある図を指している箇所を、別の図に差し替える。描き直した図は新しい ID になる */
export function replaceFigure(text: string, from: string, to: string): string {
  return text.replace(new RegExp(`figure:${from}(?![0-9a-f-])`, "gi"), `figure:${to}`);
}

/** draw.io が書き出した SVG（data URI）を文字列に戻す。ラベルの日本語が崩れないよう、UTF-8 として読む */
export function svgFromDataUri(dataUri: string): string {
  const comma = dataUri.indexOf(",");
  const body = dataUri.slice(comma + 1);
  if (!dataUri.slice(0, comma).endsWith(";base64")) return decodeURIComponent(body);
  const bytes = Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}
