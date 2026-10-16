import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

/**
 * クイズのパック（ADR-0034）。図ごと 1 つのディレクトリにまとめる。
 *
 *   <パック>/pack.json          カテゴリと難易度
 *   <パック>/quizzes/*.json      クイズの配列。ファイルは分け方の自由のためで、流す順に意味はない
 *   <パック>/figures/<名前>.drawio と <名前>.svg
 *
 * 解説の中では、図をファイルの名前で指す（`![地図](figure:world-map)`）。流すときに、作った図の ID に差し替える
 */
export type Difficulty = { name: string; level: number; description?: string };
export type Category = { name: string; description?: string; difficulties: Difficulty[] };
export type Choice = { body: string; isCorrect: boolean };
export type Quiz = {
  category: string;
  difficulty: string;
  question: string;
  choices: Choice[];
  explanation: string;
  status: "published" | "draft";
};
export type Figure = { name: string; source: string; svg: string };
export type Pack = { categories: Category[]; quizzes: Quiz[]; figures: Map<string, Figure> };

/** 図の名前。UUID（作った図の ID）と見分けられるよう、小文字・数字・ハイフンに限る */
const FIGURE_NAME = /^[a-z0-9][a-z0-9-]*$/;

/** 解説の中の図の参照（画像とリンク）。`](figure:名前)` の形だけを見る */
const FIGURE_REFERENCE = /\]\(figure:([^)\s]+)\)/g;

export function readPack(dir: string): Pack {
  const meta = JSON.parse(readFileSync(path.join(dir, "pack.json"), "utf8")) as { categories: Category[] };
  const quizzesDir = path.join(dir, "quizzes");
  const quizzes = readdirSync(quizzesDir)
    .filter((file) => file.endsWith(".json"))
    .sort()
    .flatMap((file) => JSON.parse(readFileSync(path.join(quizzesDir, file), "utf8")) as Quiz[]);

  const figuresDir = path.join(dir, "figures");
  const figures = new Map<string, Figure>();
  for (const file of safeReaddir(figuresDir).filter((file) => file.endsWith(".drawio"))) {
    const name = file.replace(/\.drawio$/, "");
    const svgPath = path.join(figuresDir, `${name}.svg`);
    let svg: string;
    try {
      svg = readFileSync(svgPath, "utf8");
    } catch {
      throw new Error(`${svgPath} がありません。tools/content/export-svg.sh で書き出してください`);
    }
    figures.set(name, { name, source: readFileSync(path.join(figuresDir, file), "utf8"), svg });
  }
  return { categories: meta.categories, quizzes, figures };
}

function safeReaddir(dir: string): string[] {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

/** 解説が指している図の名前 */
export function figureNames(explanation: string): string[] {
  return [...explanation.matchAll(FIGURE_REFERENCE)].map((match) => match[1]);
}

/** 解説の図の名前を、作った図の ID に差し替える */
export function resolveFigures(explanation: string, ids: Map<string, string>): string {
  return explanation.replace(FIGURE_REFERENCE, (whole, name: string) => {
    const id = ids.get(name);
    if (!id) throw new Error(`図 ${name} の ID がありません`);
    return `](figure:${id})`;
  });
}

/** 同じクイズかを決める鍵（ADR-0034: カテゴリと問題文） */
export const quizKey = (category: string, question: string) => `${category.trim()}\n${question.trim()}`;

/**
 * 流す前に、パックの中だけで分かる誤りを集める。選択肢の数や文字数などはアプリが確かめるので、ここでは見ない。
 * 誤りがあれば、何も流さずに止める
 */
export function validatePack(pack: Pack): string[] {
  const errors: string[] = [];
  const difficulties = new Map(pack.categories.map((c) => [c.name, new Set(c.difficulties.map((d) => d.name))]));
  const seen = new Set<string>();
  const used = new Set<string>();

  for (const name of pack.figures.keys()) {
    if (!FIGURE_NAME.test(name)) errors.push(`図の名前は小文字・数字・ハイフンにしてください: ${name}`);
  }
  pack.quizzes.forEach((quiz, index) => {
    const where = `${index + 1} 問目（${quiz.question.slice(0, 20)}）`;
    const names = difficulties.get(quiz.category);
    if (!names) errors.push(`${where}: カテゴリ「${quiz.category}」が pack.json にありません`);
    else if (!names.has(quiz.difficulty)) errors.push(`${where}: 難易度「${quiz.difficulty}」がカテゴリにありません`);

    const key = quizKey(quiz.category, quiz.question);
    if (seen.has(key)) errors.push(`${where}: 同じカテゴリに、同じ問題文がもう 1 つあります`);
    seen.add(key);

    for (const name of figureNames(quiz.explanation)) {
      used.add(name);
      if (!pack.figures.has(name)) errors.push(`${where}: 図 ${name} が figures/ にありません（.drawio と .svg）`);
    }
  });
  for (const name of pack.figures.keys()) {
    if (!used.has(name)) errors.push(`図 ${name} は、どの解説からも指されていません`);
  }
  return errors;
}
