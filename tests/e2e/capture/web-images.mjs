// web の画像（DEV-194）を作る。リンクを共有したときのカード（OGP）、favicon、Apple のアイコン。
//
// - アイコン: `apps/web/src/app/icon.svg` を描いて、favicon.ico（16・32・48）と apple-icon.png（180）にする
// - カード: README の画面の画像（docs/images/）を並べた 1200×630 の画像。`pnpm --filter e2e capture` で撮り直したあとに流す
//
// アプリを起動しなくても作れる。文字は手元のフォントで描く（ヒラギノ角ゴシックか Noto Sans JP）。
//
//   pnpm --filter e2e web-images
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const appDir = path.join(root, "apps/web/src/app");
const imagesDir = path.join(root, "docs/images");

const dataUri = (file, type) => `data:${type};base64,${readFileSync(file).toString("base64")}`;
const iconSvg = readFileSync(path.join(appDir, "icon.svg"), "utf8");

const browser = await chromium.launch();

/**
 * アイコンを、指定した大きさの PNG にする。
 * `opaque` なら四隅もアイコンの地の色で塗る。iOS は自分で角を丸め、透けたところを黒くする
 */
async function renderIcon(size, { opaque = false } = {}) {
  const page = await browser.newPage({ viewport: { width: size, height: size } });
  const background = opaque ? "#18181b" : "transparent";
  await page.setContent(
    `<style>html,body{margin:0;background:${background}}svg{display:block;width:${size}px;height:${size}px}</style>${iconSvg}`,
  );
  const png = await page.screenshot({ omitBackground: !opaque });
  await page.close();
  return png;
}

/** PNG をそのまま入れた ICO を作る（Windows Vista 以降と、いまのブラウザが読める） */
function toIco(entries) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  let offset = 6 + 16 * entries.length;
  const directory = entries.map(({ size, png }) => {
    const entry = Buffer.alloc(16);
    entry.writeUInt8(size >= 256 ? 0 : size, 0);
    entry.writeUInt8(size >= 256 ? 0 : size, 1);
    entry.writeUInt16LE(1, 4);
    entry.writeUInt16LE(32, 6);
    entry.writeUInt32LE(png.length, 8);
    entry.writeUInt32LE(offset, 12);
    offset += png.length;
    return entry;
  });
  return Buffer.concat([header, ...directory, ...entries.map(({ png }) => png)]);
}

const icons = [];
for (const size of [16, 32, 48]) icons.push({ size, png: await renderIcon(size) });
writeFileSync(path.join(appDir, "favicon.ico"), toIco(icons));
writeFileSync(path.join(appDir, "apple-icon.png"), await renderIcon(180, { opaque: true }));

// ---- カード（1200×630） ----

const card = await browser.newPage({ viewport: { width: 1200, height: 630 } });
await card.setContent(`
<style>
  * { box-sizing: border-box; }
  body {
    margin: 0; width: 1200px; height: 630px; overflow: hidden; position: relative;
    background: #fafafa; color: #18181b;
    font-family: "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans JP", sans-serif;
  }
  .text { position: absolute; left: 72px; top: 84px; width: 560px; }
  .brand { display: flex; align-items: center; gap: 20px; }
  .brand svg { width: 76px; height: 76px; }
  h1 { margin: 0; font-size: 84px; font-weight: 700; letter-spacing: -0.02em; }
  .tagline { margin: 36px 0 0; font-size: 40px; font-weight: 700; line-height: 1.4; }
  ul { margin: 32px 0 0; padding: 0; list-style: none; font-size: 26px; line-height: 1.7; color: #52525b; }
  li::before { content: "・"; }
  .shot { position: absolute; border-radius: 16px; border: 1px solid #e4e4e7; background: #fff;
          box-shadow: 0 24px 48px rgba(24, 24, 27, 0.16); overflow: hidden; }
  .answer { right: -40px; top: 60px; width: 470px; height: 627px; }
  .mobile { right: 330px; top: 250px; width: 240px; height: 372px; }
  .shot img { display: block; width: 100%; }
</style>
<div class="text">
  <div class="brand">${iconSvg}<h1>Quiz</h1></div>
  <p class="tagline">図解つきのクイズを、<br>作って解く</p>
  <ul>
    <li>カテゴリと難易度を自由に決められる</li>
    <li>解説に draw.io の図・画像・PDF</li>
    <li>アカウントなしでデモを試せる</li>
  </ul>
</div>
<div class="shot answer"><img src="${dataUri(path.join(imagesDir, "play-answer.png"), "image/png")}"></div>
<div class="shot mobile"><img src="${dataUri(path.join(imagesDir, "play-mobile.png"), "image/png")}"></div>
`);
await card.evaluate(() => document.fonts.ready);
for (const name of ["opengraph-image", "twitter-image"]) {
  await card.screenshot({ path: path.join(appDir, `${name}.png`) });
}

await browser.close();
console.log("apps/web/src/app/ に favicon.ico、apple-icon.png、opengraph-image.png、twitter-image.png を書きました");
