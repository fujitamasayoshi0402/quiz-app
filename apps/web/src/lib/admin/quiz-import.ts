import { z } from "zod";
import type { ImportQuizRequest } from "@/lib/api/generated/model";

/**
 * クイズの一括取り込みのファイルを読む。
 *
 * **API が受けるのは JSON だけ。** CSV はここで同じ形に変換する。
 * ここでは形（列や型）だけを見て、中身（カテゴリがあるか、公開の条件を満たすか）はバックエンドに任せる。
 * 中身の検証を二重に持つと、どちらかを直し忘れたときに結果が食い違う。
 */

/** 1 回に取り込める件数。バックエンド（QuizImportUseCase.MAX_ROWS）と合わせる */
export const MAX_IMPORT_ROWS = 500;

/** CSV の列。1 行目に、この名前で並べる（順番は問わない） */
export const CSV_COLUMNS = [
  "category",
  "difficulty",
  "question",
  "choice1",
  "choice2",
  "choice3",
  "choice4",
  "correct",
  "explanation",
  "status",
] as const;

const REQUIRED_COLUMNS = ["category", "difficulty", "question"] as const;

export type ParsedImport =
  | {
      ok: true;
      rows: ImportQuizRequest[];
      /** rows と同じ順の、ファイルの中の位置。エラーを利用者に伝えるときに使う（例: 「3 行目」） */
      labels: string[];
    }
  | { ok: false; errors: string[] };

/** ファイル名の拡張子で形式を決める */
export function parseImportFile(fileName: string, text: string): ParsedImport {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".csv")) return parseCsvImport(text);
  if (lower.endsWith(".json")) return parseJsonImport(text);
  return { ok: false, errors: ["CSV（.csv）か JSON（.json）のファイルを選んでください"] };
}

/**
 * ファイルの中身を文字列にする。
 *
 * Excel で「CSV」として保存すると、日本語の環境では Shift_JIS になる。UTF-8 として読めなければ Shift_JIS で読む。
 */
export function decodeImportFile(buffer: ArrayBuffer): string {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(buffer);
  } catch {
    return new TextDecoder("shift_jis").decode(buffer);
  }
}

// --- JSON -------------------------------------------------------------------

/** 形だけを見る。空の文字列や選択肢の数は、バックエンドが理由とともに返す */
const JsonQuizSchema = z.object({
  category: z.string(),
  difficulty: z.string(),
  question: z.string(),
  explanation: z.string().optional(),
  choices: z.array(z.object({ body: z.string(), isCorrect: z.boolean().optional() })).optional(),
  status: z.string().optional(),
});

/** `[...]` と、API と同じ `{"quizzes": [...]}` のどちらも受ける */
export function parseJsonImport(text: string): ParsedImport {
  let data: unknown;
  try {
    data = JSON.parse(stripBom(text));
  } catch {
    return { ok: false, errors: ["JSON として読めません。形式を確かめてください"] };
  }

  const items = Array.isArray(data) ? data : isObject(data) && Array.isArray(data.quizzes) ? data.quizzes : null;
  if (!items) return { ok: false, errors: ["クイズの配列、または quizzes に配列を持つオブジェクトにしてください"] };

  const errors: string[] = [];
  const rows = items.flatMap((item, index) => {
    const parsed = JsonQuizSchema.safeParse(item);
    if (!parsed.success) {
      const fields = [...new Set(parsed.error.issues.map((issue) => issue.path[0]).filter((key) => key != null))];
      errors.push(`${index + 1} 件目: ${fields.length > 0 ? fields.join("、") : "内容"} の形が正しくありません`);
      return [];
    }
    const { choices, ...rest } = parsed.data;
    return [{ ...rest, choices: choices?.map((choice) => ({ body: choice.body, isCorrect: choice.isCorrect ?? false })) }];
  });
  return finish(
    rows,
    rows.map((_, index) => `${index + 1} 件目`),
    errors,
  );
}

// --- CSV --------------------------------------------------------------------

/**
 * 1 行目を列名として読む。選択肢は choice1〜choice4、正解は correct に番号（1〜4）で書く。
 * 空の選択肢は飛ばす（下書きなら選択肢が揃っていなくてよい）。
 */
export function parseCsvImport(text: string): ParsedImport {
  const records = parseCsv(stripBom(text)).filter((record) => record.cells.some((cell) => cell.trim() !== ""));
  if (records.length === 0) return { ok: false, errors: ["ファイルが空です"] };

  const header = records[0].cells.map((cell) => cell.trim().toLowerCase());
  const missing = REQUIRED_COLUMNS.filter((column) => !header.includes(column));
  if (missing.length > 0) {
    return { ok: false, errors: [`1 行目に列 ${missing.join("、")} がありません。列名はサンプルを参照してください`] };
  }

  const errors: string[] = [];
  const rows: ImportQuizRequest[] = [];
  const labels: string[] = [];
  records.slice(1).forEach(({ cells, line }) => {
    const cell = (column: (typeof CSV_COLUMNS)[number]) => {
      const index = header.indexOf(column);
      return index < 0 ? "" : (cells[index] ?? "");
    };

    const correct = cell("correct").trim();
    if (correct !== "" && !/^[1-4]$/.test(correct)) {
      errors.push(`${line} 行目: correct は 1〜4 の番号で書いてください`);
      return;
    }
    const choices = ([1, 2, 3, 4] as const)
      .map((number) => ({ body: cell(`choice${number}`), isCorrect: correct === String(number) }))
      .filter((choice) => choice.body.trim() !== "");
    if (correct !== "" && !choices.some((choice) => choice.isCorrect)) {
      errors.push(`${line} 行目: correct が指す choice${correct} が空です`);
      return;
    }

    const status = cell("status").trim();
    rows.push({
      category: cell("category"),
      difficulty: cell("difficulty"),
      question: cell("question"),
      explanation: cell("explanation"),
      choices,
      ...(status === "" ? {} : { status }),
    });
    labels.push(`${line} 行目`);
  });
  return finish(rows, labels, errors);
}

/** CSV の 1 記録。[line] はファイルの中で記録が始まる行（1 始まり） */
type CsvRecord = { cells: string[]; line: number };

/**
 * RFC 4180 の CSV を読む。引用符で囲んだ値の中のカンマ・改行・`""`（引用符そのもの）を扱う。
 * 記録ごとに、ファイルの中で始まる行番号（1 始まり）を持つ。値の中に改行があると、次の記録の行番号がずれるため。
 */
export function parseCsv(text: string): CsvRecord[] {
  const records: CsvRecord[] = [];
  let cells: string[] = [];
  let value = "";
  let quoted = false;
  let line = 1;
  let startLine = 1;

  const endCell = () => {
    cells.push(value);
    value = "";
  };
  const endRecord = () => {
    endCell();
    records.push({ cells, line: startLine });
    cells = [];
  };

  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        value += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
      } else {
        if (char === "\n") line++;
        value += char;
      }
      continue;
    }
    if (char === '"' && value === "") {
      quoted = true;
    } else if (char === ",") {
      endCell();
    } else if (char === "\n" || char === "\r") {
      // CRLF は 1 つの改行として扱う
      if (char === "\r" && text[i + 1] === "\n") i++;
      endRecord();
      line++;
      startLine = line;
    } else {
      value += char;
    }
  }
  if (value !== "" || cells.length > 0) endRecord();
  return records;
}

// --- 共通 -------------------------------------------------------------------

function finish(rows: ImportQuizRequest[], labels: string[], errors: string[]): ParsedImport {
  if (errors.length > 0) return { ok: false, errors };
  if (rows.length === 0) return { ok: false, errors: ["取り込むクイズがありません"] };
  if (rows.length > MAX_IMPORT_ROWS) {
    return { ok: false, errors: [`1 回に取り込めるのは ${MAX_IMPORT_ROWS} 件までです（${rows.length} 件あります）`] };
  }
  return { ok: true, rows, labels };
}

function stripBom(text: string): string {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

// --- 取り込めなかった行 -----------------------------------------------------

/** バックエンドが 400 で返す、取り込めなかった行。ProblemDetail の追加の項目なので、定義（OpenAPI）には載らない */
const RejectedRowsSchema = z.array(z.object({ index: z.number(), messages: z.array(z.string()) }));

export type RejectedRow = { label: string; messages: string[] };

/** エラー応答の `rows` を、ファイルの中の位置（labels）に読み替える。形が違えば null */
export function rejectedRowsOf(problemRows: unknown, labels: string[]): RejectedRow[] | null {
  const parsed = RejectedRowsSchema.safeParse(problemRows);
  if (!parsed.success) return null;
  return parsed.data.map((row) => ({ label: labels[row.index] ?? `${row.index + 1} 件目`, messages: row.messages }));
}

// --- サンプル ---------------------------------------------------------------

export const SAMPLE_CSV = [
  CSV_COLUMNS.join(","),
  'AWS / インフラ,SAA,S3 で、アクセスの頻度が下がったデータを自動で安い階層へ移すのは？,S3 Intelligent-Tiering,S3 Standard,S3 One Zone-IA,S3 Glacier Deep Archive,1,"アクセスのパターンを見て、自動で階層を移します。',
  '取り出しの料金はかかりません。",published',
  'AWS / インフラ,SAA,"ALB と NLB の違いで、正しいものは？",ALB は L7、NLB は L4 で振り分ける,NLB は HTTP のヘッダで振り分けられる,ALB は固定 IP を持てる,どちらも UDP を扱える,1,,draft',
].join("\n");

export const SAMPLE_JSON = JSON.stringify(
  [
    {
      category: "AWS / インフラ",
      difficulty: "SAA",
      question: "S3 で、アクセスの頻度が下がったデータを自動で安い階層へ移すのは？",
      choices: [
        { body: "S3 Intelligent-Tiering", isCorrect: true },
        { body: "S3 Standard", isCorrect: false },
        { body: "S3 One Zone-IA", isCorrect: false },
        { body: "S3 Glacier Deep Archive", isCorrect: false },
      ],
      explanation: "アクセスのパターンを見て、自動で階層を移します。\n取り出しの料金はかかりません。",
      status: "published",
    },
  ],
  null,
  2,
);
