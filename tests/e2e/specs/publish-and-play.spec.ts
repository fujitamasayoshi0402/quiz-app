import path from "node:path";
import { expect, type Locator, test } from "@playwright/test";
import { storageStateOf, tenant } from "../support/users";

const category = "E2E 首都";
const question = "日本の首都はどこですか。";

/**
 * 管理者が作って公開したクイズを、一般ユーザーが解く（DEV-111）。
 * 管理の画面と出題の画面は別の利用者が使い、公開したものだけが出題に出る。解説の画像は、管理者がブラウザから S3 へ上げたものが
 * 一般ユーザーの画面まで届くかを見る（上げる URL、バケットの CORS、配る URL への送り直し）。
 *
 * カテゴリやクイズは `fixtures.sql` がテストの前に消すので、決まった名前で作れる
 */
test("管理者が公開したクイズを、一般ユーザーが学習モードで解き、結果と履歴で振り返る", async ({ browser }) => {
  const admin = await (await browser.newContext({ storageState: storageStateOf("admin") })).newPage();
  const member = await (await browser.newContext({ storageState: storageStateOf("member") })).newPage();

  await test.step("管理者がカテゴリと最初の難易度を作る", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/categories`);
    await admin.getByLabel("カテゴリ名").fill(category);
    await admin.getByLabel("最初の難易度").fill("入門");
    await admin.getByRole("button", { name: "作成する" }).click();
    await expect(admin).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/categories/[0-9a-f-]{36}$`));
  });

  await test.step("管理者がクイズを下書きにする。下書きは出題に出ない", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/quizzes/new`);
    await admin.getByRole("combobox").nth(0).click();
    await admin.getByRole("option", { name: category }).click();
    await admin.getByRole("combobox").nth(1).click();
    await admin.getByRole("option", { name: "入門（レベル 1）" }).click();

    await admin.getByLabel("問題文").fill(question);
    for (const [index, city] of ["大阪", "東京", "京都", "名古屋"].entries()) {
      await admin.getByPlaceholder(`選択肢 ${index + 1}`).fill(city);
    }
    await admin.getByRole("radio", { name: "選択肢 2 を正解にする" }).check();

    const explanation = admin.getByLabel("解説", { exact: true });
    await explanation.fill("1869 年に、天皇が京都から東京へ移りました。\n\n");
    await admin.locator('input[type="file"]').setInputFiles(path.join(__dirname, "../../api/fixtures/figure.png"));
    await expect(explanation).toHaveValue(/!\[画像\]\(figure:[0-9a-f-]{36}\)/);

    await admin.getByRole("button", { name: "下書きとして保存" }).click();
    await expect(admin).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
    await expect(admin.getByRole("row", { name: new RegExp(question) })).toContainText("下書き");

    await member.goto(`/t/${tenant.slug}/play`);
    await expect(member.getByText("E2E 中断と再開")).toBeVisible();
    await expect(member.getByText(category)).toHaveCount(0);
  });

  await test.step("管理者が公開する", async () => {
    await admin.getByRole("link", { name: question }).click();
    await admin.getByRole("button", { name: "公開する" }).click();
    await expect(admin).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
    await expect(admin.getByRole("row", { name: new RegExp(question) })).toContainText("公開");
  });

  await test.step("一般ユーザーが学習モードで解き、間違えるとその場で正解と解説が出る", async () => {
    await member.reload();
    await member.getByText(category).click();
    await member.getByRole("button", { name: "始める" }).click();

    await expect(member.getByText("1 / 1 問")).toBeVisible();
    await expect(member.getByText("学習モード")).toBeVisible();
    await member.getByRole("button", { name: "大阪" }).click();
    await member.getByRole("button", { name: "回答する" }).click();

    await expect(member.getByText("不正解", { exact: true })).toBeVisible();
    await expect(member.getByText("天皇が京都から東京へ移りました", { exact: false })).toBeVisible();
    await expectImageLoaded(member.getByRole("img", { name: "画像" }));
  });

  await test.step("結果に正答率と間違えた問題が出る", async () => {
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member).toHaveURL(new RegExp(`/t/${tenant.slug}/play/result\\?`));
    await expect(member.getByText("0%")).toBeVisible();
    await expect(member.getByText("1 問中 0 問正解")).toBeVisible();
    await expect(member.getByRole("heading", { name: "間違えた問題" })).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
  });

  await test.step("履歴に残り、そこから結果を開き直せる", async () => {
    await member.getByRole("banner").getByRole("link", { name: "履歴" }).click();
    const row = member.getByRole("link", { name: new RegExp(`${category}・すべての難易度`) });
    await expect(row).toContainText("0 / 1");

    await row.click();
    await expect(member.getByRole("heading", { name: "答え合わせ" })).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
  });
});

/** 画像が届いて描かれたこと。取れなかったときは、代わりの文字に替わって要素が消える */
async function expectImageLoaded(image: Locator) {
  await image.scrollIntoViewIfNeeded();
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);
}
