import { EncryptJWT, jwtDecrypt } from "jose";
import { z } from "zod";

/**
 * 共有した結果のカード（DEV-207）。共有のリンク（`/share/<トークン>`）に、暗号化して入れる。
 *
 * - **サーバーは何も保存しない。** 中身はリンクだけが持つ。消したいときは、リンクを捨てればよい
 * - 作るのは web のサーバーだけ（`app/share/route.ts`）。点数は完了 API から取り、画面から送られたものは使わない
 * - 出すのはカテゴリ名、正解数、問題数だけ。利用者とテナントは入れない
 * - 期限は付けない。貼ったリンクは、あとから開いても読める
 */
export const ShareCard = z.object({
  /** 「世界の地理・基礎」のような、範囲の名前 */
  label: z.string().min(1).max(80),
  correct: z.number().int().min(0),
  total: z.number().int().min(1),
});

export type ShareCard = z.infer<typeof ShareCard>;

let key: Promise<Uint8Array> | undefined;

/**
 * 暗号鍵は、ログインのセッションと同じ秘密から、**別の用途として**導く。
 * 同じ鍵にすると、共有のリンクとセッションの Cookie が入れ替えて読めてしまう。
 * 秘密を入れ替えると、それまでの共有のリンクは読めなくなる
 */
function encryptionKey(): Promise<Uint8Array> {
  key ??= (async () => {
    const secret = process.env.AUTH_SESSION_SECRET;
    if (!secret) throw new Error("AUTH_SESSION_SECRET が設定されていません");
    return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`share-card:${secret}`)));
  })();
  return key;
}

export async function sealShareCard(card: ShareCard): Promise<string> {
  return new EncryptJWT(ShareCard.parse(card))
    .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
    .encrypt(await encryptionKey());
}

/** 読めない（改ざん、鍵の入れ替え、形の違い）ものは、無いものとして扱う */
export async function openShareCard(token: string): Promise<ShareCard | undefined> {
  try {
    const { payload } = await jwtDecrypt(token, await encryptionKey());
    const card = ShareCard.safeParse(payload);
    return card.success && card.data.correct <= card.data.total ? card.data : undefined;
  } catch {
    return undefined;
  }
}

/** 正答率（%）。結果画面と同じく、未回答も分母に入れる */
export function rateOf(card: ShareCard): number {
  return Math.round((card.correct / card.total) * 100);
}

/** 共有のページのタイトルと説明。カードを貼った先（SNS、Slack）にも出る */
export function textOf(card: ShareCard) {
  return {
    title: `${card.label}で ${rateOf(card)}% 正解`,
    description: `${card.total} 問中 ${card.correct} 問正解。Quiz は、図解つきのクイズを作って解けるアプリです。`,
  };
}
