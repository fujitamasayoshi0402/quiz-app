import assert from "node:assert/strict";
import path from "node:path";
import { describe, it } from "node:test";
import { type Pack, figureNames, readPack, resolveFigures, validatePack } from "./pack.ts";

const testPack = path.join(import.meta.dirname, "testdata", "pack");

describe("パック", () => {
  it("カテゴリ、クイズ、図（原本と SVG）を読む", () => {
    const pack = readPack(testPack);
    assert.equal(pack.categories[0].name, "見本");
    assert.equal(pack.quizzes.length, 1);
    assert.ok(pack.figures.get("week")?.source.startsWith("<mxfile>"));
    assert.ok(pack.figures.get("week")?.svg.startsWith("<svg"));
    assert.deepEqual(validatePack(pack), []);
  });

  it("解説の画像とリンクの図の名前を、ID に差し替える", () => {
    const explanation = "![図](figure:week) と [開く](figure:week)、[外のリンク](https://example.com/figure:week)";
    assert.deepEqual(figureNames(explanation), ["week", "week"]);
    assert.equal(
      resolveFigures(explanation, new Map([["week", "0b66aaf5-727d-5da9-bf87-6756dff5b046"]])),
      "![図](figure:0b66aaf5-727d-5da9-bf87-6756dff5b046) と [開く](figure:0b66aaf5-727d-5da9-bf87-6756dff5b046)、[外のリンク](https://example.com/figure:week)",
    );
  });

  it("ID のない図は、差し替えずに止める", () => {
    assert.throws(() => resolveFigures("![図](figure:missing)", new Map()), /missing/);
  });

  it("パックの中だけで分かる誤りを集める", () => {
    const pack: Pack = readPack(testPack);
    const broken: Pack = {
      ...pack,
      quizzes: [
        { ...pack.quizzes[0], explanation: "![図](figure:missing)" },
        { ...pack.quizzes[0], difficulty: "むずかしい" },
        { ...pack.quizzes[0], category: "ないカテゴリ", question: "別の問題" },
      ],
      figures: new Map([...pack.figures, ["Bad_Name", { name: "Bad_Name", source: "<mxfile/>", svg: "<svg/>" }]]),
    };
    const errors = validatePack(broken);
    assert.ok(errors.some((e) => e.includes("図 missing が figures/ にありません")));
    assert.ok(errors.some((e) => e.includes("難易度「むずかしい」がカテゴリにありません")));
    assert.ok(errors.some((e) => e.includes("同じ問題文がもう 1 つあります")));
    assert.ok(errors.some((e) => e.includes("カテゴリ「ないカテゴリ」が pack.json にありません")));
    assert.ok(errors.some((e) => e.includes("小文字・数字・ハイフン")));
    assert.ok(errors.some((e) => e.includes("どの解説からも指されていません")));
  });
});
