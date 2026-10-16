/**
 * クイズのパックを、テナントに流し込む（ADR-0034、DEV-186）。
 *
 *   ACCESS_TOKEN=... node tools/content/load.ts <パック> --api <API のオリジン> --tenant <slug> [--dry-run] [--update] [--prune]
 *
 * 1. カテゴリと難易度がなければ作る
 * 2. 解説が指す図を、原本（.drawio）が同じものがあれば使い回し、なければ作る
 * 3. 解説の `figure:<名前>` を図の ID に差し替え、まだないクイズを取り込む（同じものかは、カテゴリと問題文で決める）
 * 4. `--update` を付けると、あるクイズのうちパックと違うものを、パックのとおりに直す。付けなければ、画面で直したものを上書きしない
 * 5. `--prune` を付けると、パックのカテゴリにあってパックにないクイズを消す（ゴミ箱に入る）
 *
 * `--dry-run` では、何を作り、直し、消すかだけを出して、何も変えない。
 * 作ったものは、作ったそばからログに出す。途中で失敗しても、どこまで入ったかが分かる
 */
import { parseArgs } from "node:util";
import { Api, ApiError, type QuizItem } from "./api.ts";
import { type Pack, type Quiz, figureNames, quizKey, readPack, resolveFigures, validatePack } from "./pack.ts";

/** 一括取り込みは 1 回 500 問まで */
const IMPORT_BATCH = 500;

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    api: { type: "string" },
    tenant: { type: "string" },
    "dry-run": { type: "boolean", default: false },
    update: { type: "boolean", default: false },
    prune: { type: "boolean", default: false },
  },
});

const [packDir] = positionals;
const token = process.env.ACCESS_TOKEN;
if (!packDir || !values.api || !values.tenant || !token) {
  console.error("使い方: ACCESS_TOKEN=... node tools/content/load.ts <パック> --api <オリジン> --tenant <slug> [--dry-run] [--update] [--prune]");
  process.exit(2);
}
const dryRun = values["dry-run"];

const pack = readPack(packDir);
const errors = validatePack(pack);
if (errors.length > 0) {
  console.error(`パックに誤りがあります（${errors.length} 件）。何も流していません`);
  for (const error of errors) console.error(`  - ${error}`);
  process.exit(1);
}

const api = new Api(values.api, values.tenant, token);
console.log(`${packDir} を ${api.origin} の ${api.slug} に流します${dryRun ? "（--dry-run。何も変えません）" : ""}`);
console.log(`  パック: カテゴリ ${pack.categories.length}、クイズ ${pack.quizzes.length}、図 ${pack.figures.size}`);

// ---- 1. カテゴリと難易度 ----

/** カテゴリの名前 → ID と、難易度の名前 → ID。dry-run で作っていないものは入らない */
const categoryIds = new Map<string, string>();
const difficultyIds = new Map<string, Map<string, string>>();

const existingCategories = new Map((await api.categories()).map((c) => [c.name, c.id]));
for (const category of pack.categories) {
  let id = existingCategories.get(category.name);
  if (!id) {
    console.log(`  カテゴリを作る: ${category.name}`);
    if (dryRun) {
      for (const difficulty of category.difficulties) console.log(`  難易度を作る: ${category.name} / ${difficulty.name}`);
      continue;
    }
    id = (await api.createCategory(category.name, category.description)).id;
  }
  categoryIds.set(category.name, id);

  const existing = new Map((await api.difficulties(id)).map((d) => [d.name, d.id]));
  const ids = new Map<string, string>();
  for (const difficulty of category.difficulties) {
    let difficultyId = existing.get(difficulty.name);
    if (!difficultyId) {
      console.log(`  難易度を作る: ${category.name} / ${difficulty.name}`);
      if (dryRun) continue;
      difficultyId = (await api.createDifficulty(id, difficulty)).id;
    }
    ids.set(difficulty.name, difficultyId);
  }
  difficultyIds.set(category.name, ids);
}

// ---- 2. 図: 原本が同じものを探す ----

const figureIds = await findExistingFigures(pack);
console.log(`  図: ${pack.figures.size} 枚のうち ${figureIds.size} 枚は、同じ原本のものがあります`);

// ---- 3. クイズを、入れるもの・直すもの・消すものに分ける ----

const existingQuizzes = new Map<string, QuizItem>();
for (const [name, id] of categoryIds) {
  for (const quiz of await api.quizzes(id)) existingQuizzes.set(quizKey(name, quiz.question), quiz);
}

const toImport: Quiz[] = [];
const toUpdate: { quiz: Quiz; existing: QuizItem }[] = [];
for (const quiz of pack.quizzes) {
  const existing = existingQuizzes.get(quizKey(quiz.category, quiz.question));
  if (!existing) toImport.push(quiz);
  else if (values.update && differs(quiz, existing)) toUpdate.push({ quiz, existing });
}
const packKeys = new Set(pack.quizzes.map((quiz) => quizKey(quiz.category, quiz.question)));
const toDelete = values.prune ? [...existingQuizzes].filter(([key]) => !packKeys.has(key)).map(([, quiz]) => quiz) : [];

console.log(`  クイズ: 入れる ${toImport.length}、直す ${toUpdate.length}、消す ${toDelete.length}`);
if (dryRun) {
  for (const quiz of toImport) console.log(`    入れる: [${quiz.category}] ${quiz.question}`);
  for (const { quiz } of toUpdate) console.log(`    直す: [${quiz.category}] ${quiz.question}`);
  for (const quiz of toDelete) console.log(`    消す: ${quiz.question}`);
  const newFigures = neededFigures([...toImport, ...toUpdate.map((u) => u.quiz)]).filter((n) => !figureIds.has(n));
  for (const name of newFigures) console.log(`    図を作る: ${name}`);
  process.exit(0);
}

// ---- 4. 図を作る（入れるものと直すものが指す図だけ） ----

for (const name of neededFigures([...toImport, ...toUpdate.map((u) => u.quiz)])) {
  if (figureIds.has(name)) continue;
  const figure = pack.figures.get(name)!;
  const { id } = await api.createFigure(figure.source, figure.svg);
  figureIds.set(name, id);
  console.log(`  図を作った: ${name} → ${id}`);
}

// ---- 5. 取り込み、直す、消す ----

for (let start = 0; start < toImport.length; start += IMPORT_BATCH) {
  const batch = toImport.slice(start, start + IMPORT_BATCH);
  try {
    const { importedCount } = await api.importQuizzes(
      batch.map((quiz) => ({ ...quiz, explanation: resolveFigures(quiz.explanation, figureIds) })),
    );
    console.log(`  クイズを取り込んだ: ${importedCount} 問`);
  } catch (error) {
    // 取り込みは 1 問でも誤りがあると、どれも入れない。どの問題かを、行の番号ではなく問題文で出す
    const rows = error instanceof ApiError ? ((error.problem?.rows ?? []) as { index: number; messages: string[] }[]) : [];
    for (const row of rows) console.error(`  取り込めない: ${batch[row.index]?.question} — ${row.messages.join("、")}`);
    throw error;
  }
}

for (const { quiz, existing } of toUpdate) {
  await api.updateQuiz(existing.id, {
    categoryId: categoryIds.get(quiz.category),
    difficultyId: difficultyIds.get(quiz.category)!.get(quiz.difficulty),
    question: quiz.question,
    explanation: resolveFigures(quiz.explanation, figureIds),
    status: quiz.status,
    // 同じ位置で本文が同じ選択肢は、ID を渡して、選ばれた数を引き継ぐ（DEV-170）
    choices: quiz.choices.map((choice, i) => ({
      ...choice,
      id: existing.choices[i]?.body === choice.body ? existing.choices[i].id : null,
    })),
  });
  console.log(`  クイズを直した: ${quiz.question}`);
}

for (const quiz of toDelete) {
  await api.deleteQuiz(quiz.id);
  console.log(`  クイズを消した（ゴミ箱へ）: ${quiz.question}`);
}

console.log("流し終えました");

// ---- 部品 ----

/** パックの図の原本と同じ原本を持つ図を、テナントから探す。図は変えられないので、原本が同じなら同じ図 */
async function findExistingFigures(pack: Pack): Promise<Map<string, string>> {
  const bySource = new Map([...pack.figures.values()].map((figure) => [figure.source, figure.name]));
  const found = new Map<string, string>();
  // 新しい順に返る。前に流したものは新しいほうにあることが多いので、全部見つかったら止める
  for (const figure of await api.figures()) {
    if (found.size === bySource.size) break;
    if (figure.kind !== "drawio") continue;
    const source = await sourceOf(figure.id);
    const name = source === undefined ? undefined : bySource.get(source);
    if (name && !found.has(name)) found.set(name, figure.id);
  }
  return found;
}

/**
 * 図の原本。原本が置き場所にない図（404）は、使い回さない。
 * ローカルの LocalStack は、再起動で S3 の中身が消え、DB の図の行だけが残る
 */
async function sourceOf(id: string): Promise<string | undefined> {
  try {
    return await api.figureSource(id);
  } catch (error) {
    if (error instanceof ApiError && error.problem?.status === 404) return undefined;
    throw error;
  }
}

function neededFigures(quizzes: Quiz[]): string[] {
  return [...new Set(quizzes.flatMap((quiz) => figureNames(quiz.explanation)))];
}

/** あるクイズが、パックと違うか。図は、使い回せる図の ID に差し替えて比べる */
function differs(quiz: Quiz, existing: QuizItem): boolean {
  const names = figureNames(quiz.explanation);
  if (names.some((name) => !figureIds.has(name))) return true;
  return (
    resolveFigures(quiz.explanation, figureIds) !== (existing.explanation ?? "") ||
    difficultyIds.get(quiz.category)?.get(quiz.difficulty) !== existing.difficultyId ||
    quiz.status !== existing.status ||
    JSON.stringify(quiz.choices.map(({ body, isCorrect }) => [body, isCorrect])) !==
      JSON.stringify(existing.choices.map(({ body, isCorrect }) => [body, isCorrect]))
  );
}
