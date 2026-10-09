import { expect, test } from "@playwright/test";
import { runSql } from "../support/database";
import { storageStateOf, tenant, users } from "../support/users";

/**
 * 公開テナントに、招待なしで参加する（DEV-130、ADR-0025）。
 * 管理者が設定の画面で公開し、どこにも所属していない人が一覧から見つけて参加する。
 *
 * 未所属の人は、ほかのテスト（roles）で「所属がない」ことを確かめている。終わったら所属を外し、テナントを非公開に戻す
 */
test.afterAll(() => {
  runSql(`
    UPDATE core.tenants SET visibility = 'private' WHERE slug = '${tenant.slug}';
    UPDATE core.tenant_members m SET deleted_at = now()
    FROM core.users u, core.tenants t
    WHERE m.user_id = u.id AND m.tenant_id = t.id AND t.slug = '${tenant.slug}' AND m.deleted_at IS NULL
      AND lower(u.email) = '${users.outsider}';
  `);
});

test("管理者が公開したテナントを、未所属の人が一覧から見つけて参加し、クイズを解ける画面に入る", async ({
  browser,
}) => {
  const admin = await (await browser.newContext({ storageState: storageStateOf("admin") })).newPage();
  const outsider = await (await browser.newContext({ storageState: storageStateOf("outsider") })).newPage();

  await test.step("非公開のうちは、一覧に出ない", async () => {
    await outsider.goto("/");
    await outsider.getByRole("link", { name: "公開されているテナントを探す" }).click();
    await expect(outsider).toHaveURL(/\/tenants$/);
    await expect(outsider.getByRole("heading", { name: "公開されているテナント" })).toBeVisible();
    await expect(outsider.getByText(tenant.name, { exact: true })).toHaveCount(0);
  });

  await test.step("管理者が公開する", async () => {
    await admin.goto(`/t/${tenant.slug}/admin/settings`);
    await admin.getByRole("button", { name: "公開する" }).click();
    await admin.getByRole("alertdialog").getByRole("button", { name: "公開する" }).click();
    await expect(admin.getByText("公開", { exact: true })).toBeVisible();
  });

  await test.step("一覧に出て、参加すると一般ユーザーとしてテナントに入る", async () => {
    await outsider.reload();
    await outsider.getByRole("button", { name: `${tenant.name} に参加する` }).click();
    await expect(outsider).toHaveURL(new RegExp(`/t/${tenant.slug}/play$`));
    await expect(outsider.getByRole("heading", { name: "クイズを選ぶ" })).toBeVisible();
    await expect(outsider.getByRole("link", { name: "管理", exact: true })).toHaveCount(0);
  });

  await test.step("一覧では参加済みになる", async () => {
    await outsider.goto("/tenants");
    await expect(outsider.getByText("参加済み")).toBeVisible();
    await expect(outsider.getByRole("button", { name: `${tenant.name} に参加する` })).toHaveCount(0);
  });
});
