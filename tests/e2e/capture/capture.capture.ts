import { expect, type Page, test } from "@playwright/test";
import { desktop, mobile, open, pause, shoot } from "./support";

/**
 * README の画面を撮る（DEV-180）。順に流す。
 *
 * 1. 見る人の GIF: 「デモとして入る」から解いて結果を見る。回答の履歴もここで付く
 * 2. スクリーンショット: 正誤と解説、履歴と正答率、スマホの幅の出題とテンポモード、管理画面
 * 3. 管理者の GIF: AI が作った下書きを見つけ、直して公開する。最後に流す（下書きが公開に替わる）
 *
 * 見る人は、共有のデモのアカウント（`/auth/demo`）で入る。管理者は、E2E の管理者（`capture.sql` がデモのテナントの管理者にする）
 */
test.describe.configure({ mode: "serial" });

const AI_QUESTION = "Amazon ECS の新しいメトリクス LiveTaskCount は、何を数えますか。";

async function enterAsDemo(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "デモとして入る" }).click();
  await expect(page).toHaveURL(/\/t\/demo\/play$/);
}

/**
 * 始める。共有のアカウントなので、前の手順で解きかけたクイズがあれば、破棄して始め直す。
 * 撮るたびに `fixtures.sql` が解きかけのものを消すので、最初の手順では出ない
 */
async function start(page: Page) {
  await page.getByRole("button", { name: "始める", exact: true }).click();
  const discard = page.getByRole("button", { name: "破棄して新しく始める" });
  await expect(
    page.getByRole("button", { name: "回答する" }).or(page.getByRole("listitem").first()).or(discard).first(),
  ).toBeVisible();
  if (await discard.isVisible()) await discard.click();
}

/** 学習モードで、カテゴリの問題を最後まで解く。選択肢は、問題ごとに `pick` 番目を選ぶ */
async function solveAll(page: Page, { pick = (i: number) => i % 2, wait = 1_500 } = {}) {
  for (let i = 0; ; i++) {
    await page.getByRole("listitem").nth(pick(i)).getByRole("button").click();
    await pause(page, 500);
    await page.getByRole("button", { name: "回答する" }).click();
    await expect(page.getByText(/^(正解|不正解)$/)).toBeVisible();
    await pause(page, wait);
    const result = page.getByRole("button", { name: "結果を見る" });
    if (await result.isVisible()) {
      await result.click();
      return;
    }
    await page.getByRole("button", { name: "次の問題へ" }).click();
  }
}

test("見る人の GIF: デモとして入り、解いて、結果を見る", async ({ browser }) => {
  const { page, finish } = await open(browser, { viewport: mobile, video: "demo-play" });
  await page.goto("/");
  await pause(page);
  await page.getByRole("button", { name: "デモとして入る" }).click();
  await expect(page).toHaveURL(/\/t\/demo\/play$/);
  await pause(page);

  await page.getByText("宇宙と天気", { exact: true }).click();
  await pause(page, 600);
  await page.getByText("学習モード").click();
  await pause(page, 600);
  await page.getByRole("button", { name: "始める" }).click();
  await pause(page, 1_000);

  await solveAll(page, { wait: 2_500 });
  await expect(page.getByText(/\d+ \/ \d+ 問/)).toBeVisible();
  await pause(page, 2_500);
  await finish();
});

test("スクリーンショット: 見る人の画面", async ({ browser }) => {
  const { page, finish } = await open(browser, { viewport: desktop });
  await enterAsDemo(page);

  await page.getByText("身近な科学", { exact: true }).click();
  await page.getByText("学習モード").click();
  await start(page);

  await page.getByRole("listitem").first().getByRole("button").click();
  await page.getByRole("button", { name: "回答する" }).click();
  await expect(page.getByText(/^(正解|不正解)$/)).toBeVisible();
  await shoot(page, "play-answer");

  await page.goto("/t/demo/history");
  await expect(page.getByText("正答率").first()).toBeVisible();
  await shoot(page, "history");
  await finish();
});

test("スクリーンショット: スマホの幅（出題とテンポモード）", async ({ browser }) => {
  const { page, finish } = await open(browser, { viewport: mobile });
  await enterAsDemo(page);

  await page.getByText("日本と世界の歴史", { exact: true }).click();
  await page.getByText("学習モード").click();
  await start(page);
  await expect(page.getByRole("button", { name: "回答する" })).toBeVisible();
  await shoot(page, "play-mobile");

  await page.goto("/t/demo/play");
  await page.getByText("ことばの由来", { exact: true }).click();
  await page.getByText("テンポモード").click();
  await start(page);
  await page.getByRole("listitem").first().getByRole("button").click();
  await expect(page.getByText(/^(正解|不正解)$/)).toBeVisible();
  await shoot(page, "play-tempo");
  await finish();
});

test("スクリーンショット: 管理画面（クイズの一覧と、AI の下書きの編集）", async ({ browser }) => {
  const { page, finish } = await open(browser, { as: "admin", viewport: desktop });
  await page.goto("/t/demo/admin/quizzes");
  // 下書きに絞る。登録順では AI の下書きが一覧の最後に来て、印が画面に入らない
  await page.getByRole("combobox").filter({ hasText: "すべての状態" }).click();
  await page.getByRole("option", { name: "下書き" }).click();
  await expect(page.getByRole("link", { name: AI_QUESTION })).toBeVisible();
  await shoot(page, "admin-quizzes");

  await page.getByRole("link", { name: AI_QUESTION }).click();
  await expect(page.getByText("AI が公式の発表をもとに作ったクイズです")).toBeVisible();
  await shoot(page, "admin-ai-draft");
  await finish();
});

test("管理者の GIF: AI の下書きを見つけ、元の発表を見ながら直して公開する", async ({ browser }) => {
  const { page, finish } = await open(browser, { as: "admin", viewport: desktop, video: "admin-ai-draft" });
  await page.goto("/t/demo/admin/quizzes");
  await expect(page.getByRole("link", { name: AI_QUESTION })).toBeVisible();
  await pause(page);

  await page.getByRole("combobox", { name: "作った人" }).click();
  await pause(page, 600);
  await page.getByRole("option", { name: "AI が作ったもの" }).click();
  await pause(page, 1_500);

  await page.getByRole("link", { name: AI_QUESTION }).click();
  await expect(page.getByText("AI が公式の発表をもとに作ったクイズです")).toBeVisible();
  await pause(page, 2_500);
  await page.getByRole("link", { name: "元の発表を開く" }).hover();
  await pause(page, 1_500);

  // 確かめて、解説を書き足す
  const explanation = page.getByRole("textbox", { name: "解説" });
  await explanation.scrollIntoViewIfNeeded();
  await explanation.click();
  // 先頭に書き足す。先頭へ移るキーは OS で違うため、カーソルを直接置く
  await explanation.evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(0, 0));
  await page.keyboard.type("**ポイント**: 動かそうとしている数（desired）ではなく、いま動いている数です。\n\n", {
    delay: 40,
  });
  await pause(page, 1_000);
  await page.getByRole("tab", { name: "プレビュー" }).click();
  await pause(page, 2_500);

  await page.getByRole("button", { name: "公開する" }).click();
  await expect(page).toHaveURL(/\/t\/demo\/admin\/quizzes$/);
  await pause(page, 800);

  // 公開したものは、AI の印を残したまま並ぶ
  await page.getByRole("combobox", { name: "作った人" }).click();
  await page.getByRole("option", { name: "AI が作ったもの" }).click();
  await expect(page.getByRole("row", { name: new RegExp(AI_QUESTION) })).toContainText("公開");
  await pause(page, 3_000);
  await finish();
});
