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
test("管理者が公開したクイズを、一般ユーザーが学習モードで解き、間違えた問題を解き直し、結果と履歴で振り返り、あとで見直す。今日の復習に出る。管理者は回答の数と選ばれた選択肢を見る", async ({ browser }) => {
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
    await expect(member.getByText("0 / 1 問")).toBeVisible();
    await expect(member.getByRole("heading", { name: "間違えた問題" })).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
  });

  await test.step("履歴のカテゴリ別の正答率から、そのカテゴリの間違えた問題をすぐ解き直せる", async () => {
    await member.getByRole("banner").getByRole("link", { name: "履歴" }).click();
    const score = member
      .getByRole("listitem")
      .filter({ hasText: category })
      .filter({ has: member.getByRole("progressbar") });
    // 1 問しか解いていないので、0% とは出さない
    await expect(score).toContainText("回答が少ない");
    await score.getByRole("button", { name: "間違えた 1 問を解き直す" }).click();

    await expect(member.getByText("1 / 1 問")).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
    // もう一度間違え、結果の画面から解き直す（次の段）
    await member.getByRole("button", { name: "大阪" }).click();
    await member.getByRole("button", { name: "回答する" }).click();
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member.getByText("0 / 1 問")).toBeVisible();
  });

  await test.step("間違えた問題は今日の復習に出て、出題対象「今日の復習」で解ける", async () => {
    await member.getByRole("banner").getByRole("link", { name: "解く" }).click();
    // ほかの spec で間違えた問題も数えるため、件数は決め打ちにしない
    await expect(member.getByText(/^今日の復習 \d+ 問$/)).toBeVisible();
    await member.getByText(category).click();
    await member.getByText("今日の復習", { exact: true }).click();
    await member.getByRole("button", { name: "始める" }).click();

    await expect(member.getByText("1 / 1 問")).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
    // もう一度間違え、結果の画面から解き直す（次の段）
    await member.getByRole("button", { name: "大阪" }).click();
    await member.getByRole("button", { name: "回答する" }).click();
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member.getByText("0 / 1 問")).toBeVisible();
  });

  await test.step("結果から、間違えた問題を同じ範囲ですぐ解き直せる", async () => {
    await member.getByRole("button", { name: "間違えた問題を解き直す" }).click();

    await expect(member.getByText("1 / 1 問")).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
    await member.getByRole("button", { name: "東京" }).click();
    await member.getByRole("button", { name: "回答する" }).click();
    await expect(member.getByText("正解", { exact: true })).toBeVisible();

    // 正解したが、あとで見直したい
    const bookmark = member.getByRole("button", { name: "あとで見直す" });
    await bookmark.click();
    await expect(bookmark).toHaveAttribute("aria-pressed", "true");

    // 正解したので、間違えた問題は残っていない
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member.getByText("100%")).toBeVisible();
    await expect(member.getByRole("button", { name: "間違えた問題を解き直す" })).toHaveCount(0);
  });

  await test.step("履歴に残り、そこから結果を開き直せる", async () => {
    await member.getByRole("banner").getByRole("link", { name: "履歴" }).click();
    // 解き直した回と、今日の復習で解いた回も並ぶ。最初の回を開く
    const row = member
      .getByRole("link", { name: new RegExp(`${category}・すべての難易度`) })
      .filter({ hasNotText: /間違えた問題|今日の復習/ });
    await expect(row).toContainText("0 / 1");

    await row.click();
    await expect(member.getByRole("heading", { name: "答え合わせ" })).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
  });

  await test.step("「あとで見直す」を押した問題だけを解き直し、外せる", async () => {
    await member.goto(`/t/${tenant.slug}/play`);
    await member.getByText(category).click();
    // 選んだ範囲に、印を付けた問題が何問あるかが出る
    await expect(member.getByText("この範囲に 1 問", { exact: false })).toBeVisible();
    await member.getByText("あとで見直す問題", { exact: true }).click();
    await member.getByRole("button", { name: "始める" }).click();

    await expect(member.getByText("1 / 1 問")).toBeVisible();
    await expect(member.getByText(question)).toBeVisible();
    await member.getByRole("button", { name: "東京" }).click();
    await member.getByRole("button", { name: "回答する" }).click();

    // 正解しても印は残る。押すと外れる
    const bookmark = member.getByRole("button", { name: "あとで見直す" });
    await expect(bookmark).toHaveAttribute("aria-pressed", "true");
    await bookmark.click();
    await expect(bookmark).toHaveAttribute("aria-pressed", "false");
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member.getByText("100%")).toBeVisible();
  });

  await test.step("正解した問題は、今日の復習から外れる", async () => {
    await member.goto(`/t/${tenant.slug}/play`);
    await member.getByText(category).click();
    await member.getByText("今日の復習", { exact: true }).click();
    await member.getByRole("button", { name: "始める" }).click();
    await expect(member.getByText("条件に合うクイズがありません", { exact: false })).toBeVisible();
  });

  await test.step("管理者のクイズの一覧に、回答の数と正答率が出る", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/quizzes`);
    // 間違えた 3 回（最初、履歴から解き直した回、今日の復習）、正解した 2 回（結果から解き直した回、あとで見直す）
    const row = admin.getByRole("row", { name: new RegExp(question) });
    await expect(row).toContainText("40%");
    await expect(row).toContainText("5 回答");
  });

  await test.step("管理者の編集画面に、選択肢ごとに選ばれた数が出る。直して保存しても数は残る", async () => {
    const expectCounts = async () => {
      await expect(admin.getByText("これまでの回答 5 件", { exact: false })).toBeVisible();
      // 大阪を 3 回（間違えた回）、東京を 2 回選んだ
      await expect(admin.getByText("3 回選ばれた（60%）", { exact: true })).toBeVisible();
      await expect(admin.getByText("2 回選ばれた（40%）", { exact: true })).toBeVisible();
      await expect(admin.getByText("0 回選ばれた（0%）", { exact: true })).toHaveCount(2);
    };
    await admin.getByRole("link", { name: question }).click();
    await expectCounts();

    // 選択肢の文言を直しても、その欄の選択肢として数え続ける
    await admin.getByPlaceholder("選択肢 1").fill("大阪府");
    await admin.getByRole("button", { name: "公開したまま保存" }).click();
    await expect(admin).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
    await admin.getByRole("link", { name: question }).click();
    await expect(admin.getByPlaceholder("選択肢 1")).toHaveValue("大阪府");
    await expectCounts();
  });
});

/** 画像が届いて描かれたこと。取れなかったときは、代わりの文字に替わって要素が消える */
async function expectImageLoaded(image: Locator) {
  await image.scrollIntoViewIfNeeded();
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);
}
