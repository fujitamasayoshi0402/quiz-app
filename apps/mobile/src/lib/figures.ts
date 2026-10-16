/**
 * 解説の本文から解説図を指す書き方（ADR-0020）。web の `lib/figures.ts` と同じ拾い方にする。
 *
 * `figure:` のあとが UUID の形でなければ、図として扱わない。
 */
const REFERENCE = /^figure:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;

/** URL が図を指していれば、その ID（小文字）。指していなければ undefined */
export function figureIdOf(url: string | null | undefined): string | undefined {
  return url?.match(REFERENCE)?.[1].toLowerCase();
}

/** 図を取りに行くパス。API が所属を確かめ、期限つきの署名付き URL へ送る（ADR-0017） */
export function figurePath(tenant: string, id: string): string {
  return `/api/t/${encodeURIComponent(tenant)}/play/figures/${id}`;
}

/** 本文の中に出す画像のパス。PDF なら 1 ページ目の画像、ほかは図そのもの（ADR-0021） */
export function figurePreviewPath(tenant: string, id: string): string {
  return `${figurePath(tenant, id)}/preview`;
}
