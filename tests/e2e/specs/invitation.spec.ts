import { expect, test } from "@playwright/test";
import { storageStateOf, tenant, users } from "../support/users";

/**
 * 招待（DEV-66）。管理者がリンクを作り、招待された人だけが参加できる。
 * 利用者ごとに別のブラウザのコンテキストを使い、同じリンクを 3 人が開く
 */
test("管理者が作ったリンクで、招待された人だけが参加できる", async ({ browser }) => {
  const admin = await (await browser.newContext({ storageState: storageStateOf("admin") })).newPage();
  const outsider = await (await browser.newContext({ storageState: storageStateOf("outsider") })).newPage();
  const invitee = await (await browser.newContext({ storageState: storageStateOf("invitee") })).newPage();

  await test.step("管理者がリンクを作る", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/invitations`);
    await admin.getByLabel("メールアドレス").fill(users.invitee);
    await admin.getByRole("button", { name: "リンクを作る" }).click();
  });
  const link = await admin.getByLabel("招待のリンク").inputValue();
  expect(link).toMatch(/\/invitations\/[A-Za-z0-9_-]{43}$/);

  await test.step("別のアドレスでログインしている人は、リンクを持っていても入れない", async () => {
    await outsider.goto(link);
    await expect(outsider.getByText("この招待は、ログインしているアカウント宛てではありません")).toBeVisible();
    // 招待先のテナントも明かさない
    await expect(outsider.getByText(tenant.name)).toHaveCount(0);
  });

  await test.step("招待された人は、招待先を確かめてから参加する", async () => {
    await invitee.goto(link);
    await expect(invitee.getByText(`${tenant.name} への招待`)).toBeVisible();
    await expect(invitee.getByText("一般ユーザーとして招待されています", { exact: false })).toBeVisible();

    await invitee.getByRole("button", { name: "参加する" }).click();
    await expect(invitee).toHaveURL(new RegExp(`/t/${tenant.slug}/play$`));
  });

  await test.step("使い終わった招待は一覧から消え、もう一度開くと使用済みと出る", async () => {
    await admin.reload();
    await expect(admin.getByText("受け入れを待っている招待はありません")).toBeVisible();

    await invitee.goto(link);
    await expect(invitee.getByText("この招待は使用済みです")).toBeVisible();
  });
});
