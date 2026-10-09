/**
 * web の整形（DEV-78）。lint（ESLint）は書き方の誤りを、Prettier は見た目を受け持つ。
 *
 * 1 行の長さ（120）とインデントは、リポジトリのルートの .editorconfig から取る。Kotlin（ktlint）と同じ値で、ここには書かない。
 * そのほかは Prettier の既定のまま（ダブルクォート、セミコロンあり、末尾のカンマあり）。既定から変えるほど、エディタの設定とずれやすい。
 *
 * @type {import("prettier").Config}
 */
const config = {
  // Tailwind のクラスを、公式の順に並べ替える。書き手ごとの並びの揺れを、レビューで見なくて済む。
  // v4 は設定を CSS に持つため、その場所を渡す。cn / cva の引数に書いたクラスも並べ替える
  plugins: ["prettier-plugin-tailwindcss"],
  tailwindStylesheet: "./src/app/globals.css",
  tailwindFunctions: ["cn", "cva"],
};

export default config;
