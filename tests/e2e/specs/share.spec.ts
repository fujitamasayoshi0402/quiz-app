import { expect, test } from "@playwright/test";
import { expectNoHorizontalScroll } from "../support/layout";
import { storageStateOf, tenant } from "../support/users";

/**
 * 結果を共有する（DEV-207）。共有のリンクは web のサーバーが作り、ログインしていない人も開ける。
 *
 * 点数を完了 API から取ることと、トークンを偽れないことは、単体テスト（`app/share/route.test.ts`、`lib/share/token.test.ts`）が確かめる。
 * ここでは、画面からリンクを作り、別の人として開けることと、カードの画像が返ることを見る。クイズは `fixtures.sql` が入れる 2 問
 */
test.use({ storageState: storageStateOf("member"), viewport: { width: 360, height: 740 } });

test("結果画面で共有のリンクを作り、ログインしていない人が開くと、カテゴリと正答率とカードが出る", async ({
  page,
  browser,
}) => {
  let url = "";

  await test.step("学習モードで 2 問を解いて、結果画面を開く", async () => {
    await page.goto(`/t/${tenant.slug}/play`);
    await page.getByText("E2E 中断と再開").click();
    await page.getByText("学習モード").click();
    await page.getByRole("button", { name: "始める" }).click();
    for (const next of ["次の問題へ", "結果を見る"]) {
      await page.getByRole("listitem").first().getByRole("button").click();
      await page.getByRole("button", { name: "回答する" }).click();
      await page.getByRole("button", { name: next }).click();
    }
    await expect(page.getByRole("heading", { name: "結果" })).toBeVisible();
  });

  await test.step("「結果を共有する」で、共有のリンクとカードの画像が出る", async () => {
    await page.getByRole("button", { name: "結果を共有する" }).click();
    const link = page.getByRole("textbox", { name: "共有のリンク" });
    await expect(link).toHaveValue(/\/share\/[\w.-]+$/);
    url = await link.inputValue();

    const preview = page.getByRole("img", { name: "結果のカード（正答率と正解の数）" });
    await expect(preview).toBeVisible();
    expect(await preview.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1200);
    await expectNoHorizontalScroll(page);
  });

  await test.step("「画像を保存」で、カードの画像を PNG で保存できる", async () => {
    const download = page.waitForEvent("download");
    await page.getByRole("link", { name: "画像を保存" }).click();
    expect((await download).suggestedFilename()).toBe("quiz-result.png");
  });

  await test.step("ログインしていない人が開くと、カテゴリと正答率が出て、アプリへの入口がある", async () => {
    const context = await browser.newContext({ viewport: { width: 360, height: 740 } });
    const visitor = await context.newPage();
    await visitor.goto(url);
    await expect(visitor.getByRole("heading", { name: "E2E 中断と再開" })).toBeVisible();
    await expect(visitor.getByText("正答率")).toBeVisible();
    await expect(visitor.getByRole("link", { name: "このアプリで解いてみる" })).toHaveAttribute("href", "/");
    await expectNoHorizontalScroll(visitor);

    // カードの画像は、貼った先（SNS、Slack）がログインせずに取りに来る
    const image = await visitor.locator('meta[property="og:image"]').getAttribute("content");
    const response = await visitor.request.get(image ?? "");
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toBe("image/png");
    await context.close();
  });

  await test.step("書き換えたリンクは、使えない旨を出す", async () => {
    await page.goto(`${url.slice(0, -4)}AAAA`);
    await expect(page.getByText("このリンクは使えません")).toBeVisible();
  });
});
