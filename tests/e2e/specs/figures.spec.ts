import { expect, test } from "@playwright/test";
import { storageStateOf, tenant } from "../support/users";

/**
 * 管理者が、図の一覧から使っていない図を消す（DEV-166）。使われている図からは、使っている問題の編集画面へ移れる（DEV-167）。
 *
 * 図は、ログインした管理者のセッションのまま API で置く。draw.io の画面（`embed.diagrams.net`）には依存しない。
 * 使われている図を消せないことと、ゴミ箱からの復活を止めることは、API テスト（`FigureDeletionApiTest`）が見る。
 * 図は `fixtures.sql` がテストの前に消す
 */
test.use({ storageState: storageStateOf("admin") });

test("管理者が、使っていない図を、確かめてから一覧から消す", async ({ page }) => {
  const created = await page.request.post(`/api/t/${tenant.slug}/admin/figures`, {
    data: {
      source: "<mxfile><diagram name='e2e'>消す図</diagram></mxfile>",
      svg: '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20"/></svg>',
    },
  });
  expect(created.status()).toBe(201);
  const { id } = (await created.json()) as { id: string };
  const row = page.getByRole("listitem").filter({ has: page.locator(`img[src*="${id}"]`) });

  await test.step("管理画面の「図」に、使っていない図として出る", async () => {
    await page.goto(`/t/${tenant.slug}/admin`);
    await page.getByRole("link", { name: "図", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/figures$`));
    await expect(row).toContainText("draw.io の図");
    await expect(row).toContainText("どの問題も使っていません");
  });

  await test.step("消す前に、元に戻せないことを示す。やめれば残る", async () => {
    await row.getByRole("button", { name: "消す" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("元に戻せません");
    await dialog.getByRole("button", { name: "やめる" }).click();
    await expect(dialog).toBeHidden();
    await expect(row).toBeVisible();
  });

  await test.step("消すと、一覧から消える", async () => {
    await row.getByRole("button", { name: "消す" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "消す" }).click();
    await expect(dialog).toBeHidden();
    await expect(row).toHaveCount(0);
  });
});

/** 再開のテストのカテゴリと難易度（`fixtures.sql`）。下書きは出題に出ないので、再開のテストに響かない */
const resumeCategory = "fd6907c9-e750-46ec-8c9a-19d47a8f35fa";
const resumeDifficulty = "74a63077-7b91-4bdf-beed-1e7739108a3d";

test("使われている図は消せず、使っている問題の編集画面へ移れる", async ({ page }) => {
  const figure = await page.request.post(`/api/t/${tenant.slug}/admin/figures`, {
    data: {
      source: "<mxfile><diagram name='e2e'>使われる図</diagram></mxfile>",
      svg: '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><circle r="10"/></svg>',
    },
  });
  expect(figure.status()).toBe(201);
  const { id } = (await figure.json()) as { id: string };
  const question = "図を使っている下書きの問題";
  const quiz = await page.request.post(`/api/t/${tenant.slug}/admin/quizzes`, {
    data: {
      categoryId: resumeCategory,
      difficultyId: resumeDifficulty,
      question,
      explanation: `解説\n\n![図](figure:${id})`,
      choices: [1, 2, 3, 4].map((n) => ({ body: `選択肢 ${n}`, isCorrect: n === 1 })),
      status: "draft",
    },
  });
  expect(quiz.status()).toBe(201);
  const { id: quizId } = (await quiz.json()) as { id: string };
  const row = page.getByRole("listitem").filter({ has: page.locator(`img[src*="${id}"]`) });

  await page.goto(`/t/${tenant.slug}/admin/figures`);
  await expect(row).toContainText("公開中・下書きの問題 1 件で使用中");
  await expect(row.getByRole("button", { name: "消す" })).toBeDisabled();

  await row.getByRole("link", { name: question }).click();
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes/${quizId}$`));
  await expect(page.getByLabel("問題文")).toHaveValue(question);
});
