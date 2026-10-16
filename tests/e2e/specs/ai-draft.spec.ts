import { expect, test } from "@playwright/test";
import { expectNoHorizontalScroll } from "../support/layout";
import { storageStateOf, tenant } from "../support/users";

const category = "E2E 技術ニュース";
const question = "Amazon ECS の新しいメトリクス LiveTaskCount は、何を数えますか。";
const source = "https://aws.amazon.com/about-aws/whats-new/2026/10/amazon-ecs-livetaskcount/";

/**
 * AI が技術ニュースから作った下書きを、管理者が確かめて公開し、一般ユーザーが解く（ADR-0031、DEV-162）。
 * 下書きは、受け箱から取り込んだときと同じ形で `fixtures.sql` が入れる。スマホの幅（360px）で流し、各画面が横にはみ出さないことも見る
 */
test.use({ viewport: { width: 360, height: 740 } });

test("管理者が AI の下書きを見つけ、元の発表を見ながら公開し、一般ユーザーが解くと、AI が作ったことと出典が示される", async ({
  browser,
}) => {
  const admin = await (await browser.newContext({ storageState: storageStateOf("admin") })).newPage();
  const member = await (await browser.newContext({ storageState: storageStateOf("member") })).newPage();

  await test.step("管理者が、一覧を AI が作ったものに絞り込み、印の付いた下書きを見つける", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/quizzes`);
    await admin.getByRole("combobox", { name: "作った人" }).click();
    await admin.getByRole("option", { name: "AI が作ったもの" }).click();

    await expect(admin.getByRole("row")).toHaveCount(2); // 見出しの行と、AI の下書き
    const row = admin.getByRole("row", { name: new RegExp(question) });
    await expect(row).toContainText("下書き");
    await expect(row).toContainText("AI");
    await expectNoHorizontalScroll(admin);
  });

  await test.step("編集の画面に、AI が作ったことと元の発表へのリンクが出る。確かめて公開する", async () => {
    await admin.getByRole("link", { name: question }).click();
    await expect(admin.getByText("AI が公式の発表をもとに作ったクイズです")).toBeVisible();
    await expect(admin.getByRole("link", { name: "元の発表を開く" })).toHaveAttribute("href", source);
    await expectNoHorizontalScroll(admin);

    await admin.getByRole("button", { name: "公開する" }).click();
    await expect(admin).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
  });

  await test.step("一般ユーザーが解いて間違えると、解説の下に、AI が作って管理者が確かめたことと元の発表が出る", async () => {
    await member.goto(`/t/${tenant.slug}/play`);
    await member.getByText(category).click();
    await member.getByText("学習モード").click();
    await member.getByRole("button", { name: "始める" }).click();

    await member.getByRole("button", { name: "動かそうとしているタスクの数" }).click();
    await member.getByRole("button", { name: "回答する" }).click();

    await expect(member.getByText("不正解", { exact: true })).toBeVisible();
    await expect(member.getByText("AI が公式の発表をもとに作り、管理者が確かめて公開した問題です。")).toBeVisible();
    await expect(member.getByRole("link", { name: "元の発表", exact: true })).toHaveAttribute("href", source);
    await expectNoHorizontalScroll(member);
  });

  await test.step("結果の間違えた問題にも出る", async () => {
    await member.getByRole("button", { name: "結果を見る" }).click();
    await expect(member.getByText("0 / 1 問")).toBeVisible();
    await expect(member.getByRole("link", { name: "元の発表", exact: true })).toHaveAttribute("href", source);
    await expectNoHorizontalScroll(member);
  });
});
