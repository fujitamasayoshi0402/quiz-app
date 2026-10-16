import { mkdirSync } from "node:fs";
import path from "node:path";
import type { Browser, BrowserContext, Page } from "@playwright/test";
import { type Role, storageStateOf } from "../support/users";

/** README から読む画像の置き場所 */
export const imagesDir = path.resolve(__dirname, "../../../docs/images");

/** GIF にする前の動画の置き場所（make-gifs.sh が読む）。コミットしない */
export const videosDir = path.join(__dirname, "output", "videos");

export const desktop = { width: 1280, height: 800 };
export const mobile = { width: 390, height: 844 };

/**
 * 押した場所に輪を出す。Playwright の動画にはマウスのカーソルが映らず、どこを押したかが分からない
 */
const clickMarker = `
  addEventListener("pointerdown", (event) => {
    const dot = document.createElement("div");
    Object.assign(dot.style, {
      position: "fixed", left: event.clientX - 18 + "px", top: event.clientY - 18 + "px",
      width: "36px", height: "36px", borderRadius: "9999px", pointerEvents: "none", zIndex: "2147483647",
      background: "rgba(59, 130, 246, 0.35)", border: "2px solid rgba(59, 130, 246, 0.9)",
      transition: "opacity 600ms ease-out, transform 600ms ease-out",
    });
    document.documentElement.appendChild(dot);
    requestAnimationFrame(() => { dot.style.opacity = "0"; dot.style.transform = "scale(1.8)"; });
    setTimeout(() => dot.remove(), 700);
  }, true);
`;

type Options = {
  /** 保存した Cookie の利用者。省略するとログインしていない状態から始める */
  as?: Role;
  viewport: { width: number; height: number };
  /** 撮る動画の名前（`<名前>.webm`）。省略すると動画を撮らない */
  video?: string;
};

/** 撮るための画面を開く。終わったら `finish` を呼ぶと、動画を名前を付けて残す */
export async function open(browser: Browser, { as, viewport, video }: Options) {
  const context: BrowserContext = await browser.newContext({
    viewport,
    storageState: as ? storageStateOf(as) : { cookies: [], origins: [] },
    ...(video ? { recordVideo: { dir: path.join(videosDir, "raw"), size: viewport } } : {}),
  });
  await context.addInitScript(clickMarker);
  const page = await context.newPage();

  const finish = async () => {
    const recording = page.video();
    await context.close();
    if (video && recording) {
      mkdirSync(videosDir, { recursive: true });
      await recording.saveAs(path.join(videosDir, `${video}.webm`));
      await recording.delete();
    }
  };
  return { page, finish };
}

/** 見る人が読めるように、間を置く */
export const pause = (page: Page, ms = 1_200) => page.waitForTimeout(ms);

/**
 * `docs/images/<名前>.png` に書く。読み込みの案内やアニメーションが落ち着いてから撮る。
 *
 * パソコンの幅では、中身（ヘッダと本文）のまわりだけを切り出す。中身は中央に寄せた幅に収まり、
 * 画面ごと撮ると左右と下が余白になって、README では文字が小さくなる。縦に長い画面は、上から `maxHeight` までにする
 */
export async function shoot(page: Page, name: string, { maxHeight = 960 } = {}) {
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(500);
  const margin = 24;
  const content = await page.evaluate(() => {
    const boxes = [...document.querySelectorAll("header > *, main > *")].map((element) =>
      element.getBoundingClientRect(),
    );
    // 全画面の作り（テンポモード）では、見えている範囲をそのまま撮る
    if (boxes.length === 0) boxes.push(new DOMRect(0, 0, window.innerWidth, window.innerHeight));
    return {
      left: Math.min(...boxes.map((box) => box.left)),
      right: Math.max(...boxes.map((box) => box.right)),
      bottom: Math.max(...boxes.map((box) => box.bottom + window.scrollY)),
      width: document.documentElement.clientWidth,
    };
  });
  const left = Math.max(0, Math.floor(content.left) - margin);
  const right = Math.min(content.width, Math.ceil(content.right) + margin);
  await page.screenshot({
    path: path.join(imagesDir, `${name}.png`),
    fullPage: true,
    clip: { x: left, y: 0, width: right - left, height: Math.min(Math.ceil(content.bottom) + margin, maxHeight) },
    animations: "disabled",
  });
}
