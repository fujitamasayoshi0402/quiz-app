import { expect, test } from "@playwright/test";
import { runSql } from "../support/database";
import { storageStateOf, users } from "../support/users";

const mine = { slug: "e2e-mine", name: "E2E わたしのテナント" } as const;
const category = "E2E 自分のカテゴリ";
const question = "E2E 自分で作った問題";

/**
 * どこにも所属していない人が、自分のテナントを作り、クイズを作って解く（DEV-139、ADR-0028）。
 *
 * 未所属の人は、ほかのテスト（roles）で「所属がない」ことを確かめている。終わったら作ったテナントを消す（論理削除）。
 * テストの前にも `fixtures.sql` が消すので、途中で止まって残っても、同じ slug で作り直せる
 */
test.afterAll(() => {
  runSql(`
    UPDATE core.tenants t SET deleted_at = now()
    FROM core.users u
    WHERE t.created_by = u.id AND t.deleted_at IS NULL AND lower(u.email) = '${users.outsider}';
  `);
});

test.use({ storageState: storageStateOf("outsider") });

test("未所属の人が自分のテナントを作り、クイズを作って解く。作ったテナントは公開できず、2 つ目は作れない", async ({
  page,
}) => {
  await test.step("所属がない画面から、テナントを作る", async () => {
    await page.goto("/");
    await page.getByRole("link", { name: "自分のテナントを作る" }).click();
    await expect(page).toHaveURL(/\/tenants\/new$/);

    await page.getByLabel("テナントの名前").fill(mine.name);
    await page.getByLabel("URL に使う名前").fill(mine.slug);
    await expect(page.getByText(`/t/${mine.slug}`)).toBeVisible();
    await page.getByRole("button", { name: "作る", exact: true }).click();

    // 中身は空なので、まずカテゴリを作る画面へ移る
    await expect(page).toHaveURL(new RegExp(`/t/${mine.slug}/admin/categories$`));
    await expect(page.getByText("カテゴリがありません")).toBeVisible();
  });

  await test.step("管理者として、カテゴリとクイズを作って公開する", async () => {
    await page.getByLabel("カテゴリ名").fill(category);
    await page.getByLabel("最初の難易度").fill("入門");
    await page.getByRole("button", { name: "作成する" }).click();
    await expect(page).toHaveURL(new RegExp(`/t/${mine.slug}/admin/categories/[0-9a-f-]{36}$`));

    await page.goto(`/t/${mine.slug}/admin/quizzes/new`);
    await page.getByRole("combobox").nth(0).click();
    await page.getByRole("option", { name: category }).click();
    await page.getByRole("combobox").nth(1).click();
    await page.getByRole("option", { name: "入門（レベル 1）" }).click();
    await page.getByLabel("問題文").fill(question);
    for (const [index, choice] of ["あ", "い", "う", "え"].entries()) {
      await page.getByPlaceholder(`選択肢 ${index + 1}`).fill(choice);
    }
    await page.getByRole("radio", { name: "選択肢 1 を正解にする" }).check();
    await page.getByLabel("解説", { exact: true }).fill("「あ」が正解です。");
    await page.getByRole("button", { name: "公開する" }).click();
    await expect(page.getByRole("row", { name: new RegExp(question) })).toContainText("公開");
  });

  await test.step("作ったクイズを解く", async () => {
    await page.getByRole("banner").getByRole("link", { name: "解く" }).click();
    await expect(page).toHaveURL(new RegExp(`/t/${mine.slug}/play$`));
    await page.getByText(category).click();
    await page.getByRole("button", { name: "始める" }).click();
    await page.getByRole("button", { name: /あ$/ }).click();
    await page.getByRole("button", { name: "回答する" }).click();
    await expect(page.getByText("正解", { exact: true })).toBeVisible();
  });

  await test.step("設定の画面では、公開できないことを示す", async () => {
    await page.goto(`/t/${mine.slug}/admin/settings`);
    await expect(page.getByText("利用者が作ったテナントのため公開できません", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "公開する" })).toHaveCount(0);
  });

  await test.step("ヘッダのメニューから作る画面を開くと、2 つ目は作れないことを先に示す", async () => {
    await page.getByRole("banner").getByRole("button", { name: mine.name }).click();
    await page.getByRole("menuitem", { name: "自分のテナントを作る" }).click();
    await expect(page.getByText("テナントはすでに作ってあります")).toBeVisible();
    await expect(page.getByLabel("テナントの名前")).toHaveCount(0);
  });
});
