import { expect, test } from "@playwright/test";
import { storageStateOf, tenant } from "../support/users";

/**
 * ロールによって入れる画面が変わること。判定はバックエンドが行い、画面は入口で案内を出す（DEV-76）。
 * ここでは、利用者から見てそうなっているかを確かめる
 */
test.describe("管理者", () => {
  test.use({ storageState: storageStateOf("admin") });

  test("所属が 1 つならテナントへ直接移り、ヘッダの「管理」から管理画面を使える", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/play$`));

    await page.getByRole("link", { name: "管理", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/admin/quizzes$`));
    await expect(page.getByRole("link", { name: "招待", exact: true })).toBeVisible();
  });
});

test.describe("一般ユーザー", () => {
  test.use({ storageState: storageStateOf("member") });

  test("ヘッダに「管理」が出ず、管理画面の URL を開いても入れない", async ({ page }) => {
    await page.goto(`/t/${tenant.slug}/play`);
    // テナント名は所属の一覧が届いてから出る。届く前に「管理」が無いことを確かめても意味がない
    await expect(page.getByRole("banner").getByText(tenant.name)).toBeVisible();
    await expect(page.getByRole("link", { name: "管理", exact: true })).toHaveCount(0);

    await page.goto(`/t/${tenant.slug}/admin/quizzes`);
    await expect(page.getByText("管理者だけが使える画面です")).toBeVisible();
    await expect(page.getByRole("link", { name: "招待", exact: true })).toHaveCount(0);
  });
});

test.describe("どこにも所属していない人", () => {
  test.use({ storageState: storageStateOf("outsider") });

  test("テナントの一覧は空で、テナントの画面は存在しないものとして扱われる", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText("所属しているテナントがありません")).toBeVisible();

    await page.goto(`/t/${tenant.slug}/admin/quizzes`);
    await expect(page.getByText("テナントが見つかりません")).toBeVisible();

    // 所属していないテナントと存在しないテナントを、バックエンドは同じ 404 で返す
    await page.goto(`/t/${tenant.slug}/play`);
    await expect(page.getByText("指定されたテナントは存在しません")).toBeVisible();
  });
});
