import { expect, type Page } from "@playwright/test";

/** 開発ガイドライン「画面の幅」。360px で横スクロールを出さない */
export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBe(0);
}
