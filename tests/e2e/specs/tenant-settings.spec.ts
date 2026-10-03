import { expect, test } from "@playwright/test";
import { storageStateOf, tenant } from "../support/users";

/**
 * テナントの公開設定（DEV-129、ADR-0025）。切り替える前に、何が起きるかを確かめるダイアログが出る。
 * テナントは `fixtures.sql` がテストの前に非公開へ戻す
 */
test.use({ storageState: storageStateOf("admin") });

test("管理者が、確かめてから公開し、非公開に戻す", async ({ page }) => {
  await page.goto(`/t/${tenant.slug}/admin`);
  await page.getByRole("link", { name: "設定", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/settings$`));
  await expect(page.getByText("非公開", { exact: true })).toBeVisible();

  await test.step("公開する前に、誰でも参加できるようになると示す。やめれば変わらない", async () => {
    await page.getByRole("button", { name: "公開する" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("一般ユーザーとして参加できるようになります");
    await dialog.getByRole("button", { name: "やめる" }).click();
    await expect(page.getByText("非公開", { exact: true })).toBeVisible();
  });

  await test.step("公開する", async () => {
    await page.getByRole("button", { name: "公開する" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "公開する" }).click();
    await expect(page.getByText("公開", { exact: true })).toBeVisible();
  });

  await test.step("非公開に戻すときは、参加した人が残ると示す", async () => {
    await page.getByRole("button", { name: "非公開に戻す" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("所属したまま残ります");
    await dialog.getByRole("button", { name: "非公開に戻す" }).click();
    await expect(page.getByText("非公開", { exact: true })).toBeVisible();
  });
});
