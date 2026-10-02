import { expect, type Page, test } from "@playwright/test";
import { storageStateOf, tenant } from "../support/users";

/**
 * 解いている途中でやめて、あとで続きから解く（DEV-111）。スマホの幅（360px）で流し、各画面が横にはみ出さないことも見る。
 *
 * 続きの位置はサーバーが持たず、出題順と回答済みの一覧から画面が導く。模試モードであることは、この端末のブラウザが覚えている。
 * どちらも画面の側の仕組みなので、API のテストでは確かめられない。クイズは `fixtures.sql` が入れる 2 問
 */
test.use({ storageState: storageStateOf("member"), viewport: { width: 360, height: 740 } });

test("模試モードを 1 問でやめ、出題の画面から再開して最後まで解く", async ({ page }) => {
  let first = "";

  await test.step("模試モードで始め、1 問目に答えたところでやめる", async () => {
    await page.goto(`/t/${tenant.slug}/play`);
    await page.getByText("E2E 中断と再開").click();
    await page.getByText("模試モード").click();
    await expectNoHorizontalScroll(page);
    await page.getByRole("button", { name: "始める" }).click();

    await expect(page.getByText("1 / 2 問")).toBeVisible();
    first = await questionOf(page);
    await expectNoHorizontalScroll(page);
    await page.getByRole("listitem").first().getByRole("button").click();
    await page.getByRole("button", { name: "回答する" }).click();

    // 模試モードは正誤を伏せて次へ進む
    await expect(page.getByText("2 / 2 問")).toBeVisible();
    await expect(page.getByText("不正解", { exact: true })).toHaveCount(0);
    await page.getByRole("banner").getByRole("link", { name: "解く" }).click();
  });

  await test.step("出題の画面に、やめたクイズが出る", async () => {
    await expect(page.getByText("中断中のクイズがあります")).toBeVisible();
    await expect(page.getByText("2 問中 1 問回答済み")).toBeVisible();
    await expectNoHorizontalScroll(page);
  });

  await test.step("再開すると、模試モードのまま 2 問目から続く", async () => {
    await page.getByRole("button", { name: "再開する" }).click();
    await expect(page.getByText("2 / 2 問")).toBeVisible();
    await expect(page.getByText("模試モード")).toBeVisible();
    expect(await questionOf(page)).not.toBe(first);

    await page.getByRole("listitem").first().getByRole("button").click();
    await page.getByRole("button", { name: "回答する" }).click();
  });

  await test.step("最後に答えると結果へ移り、2 問とも答え合わせが出る", async () => {
    await expect(page.getByRole("heading", { name: "答え合わせ" })).toBeVisible();
    await expect(page.getByText("Q1")).toBeVisible();
    await expect(page.getByText("Q2")).toBeVisible();
    await expectNoHorizontalScroll(page);

    // 終えたので、出題の画面にはもう出ない
    await page.getByRole("link", { name: "もう一度" }).click();
    await expect(page.getByText("E2E 中断と再開")).toBeVisible();
    await expect(page.getByText("中断中のクイズがあります")).toHaveCount(0);
  });
});

async function questionOf(page: Page): Promise<string> {
  return (await page.locator("[data-slot=card-title]").first().textContent()) ?? "";
}

/** 開発ガイドライン「画面の幅」。360px で横スクロールを出さない */
async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBe(0);
}
