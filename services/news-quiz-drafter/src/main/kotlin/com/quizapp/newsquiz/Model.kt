package com.quizapp.newsquiz

import java.time.Instant

/** RSS の 1 件（公式の発表）。[description] は HTML を外した説明 */
data class NewsItem(
    val guid: String,
    val title: String,
    val url: String,
    val description: String,
    val publishedAt: Instant?,
)

/** AI が書いたクイズ。受け箱に置く前に [problems] で確かめる */
data class DraftQuiz(val question: String, val choices: List<DraftChoice>, val explanation: String) {
    /**
     * そのまま公開できる形か（quiz-service の取り込みと同じ決まりに、長さの目安を足したもの）。満たさない理由を返す。
     * quiz-service の側でも確かめ直す（`NewsQuizDraft`）
     */
    fun problems(): List<String> = buildList {
        if (question.isBlank() || question.length > MAX_QUESTION) add("問題文が空か、長すぎます")
        if (choices.size != CHOICE_COUNT) add("選択肢が $CHOICE_COUNT つではありません")
        if (choices.count { it.isCorrect } != 1) add("正解が 1 つではありません")
        if (choices.any { it.body.isBlank() || it.body.length > MAX_CHOICE }) add("選択肢が空か、長すぎます")
        if (choices.map { it.body.trim() }.toSet().size != choices.size) add("同じ選択肢があります")
        if (explanation.isBlank() || explanation.length > MAX_EXPLANATION) add("解説が空か、長すぎます")
    }

    companion object {
        const val CHOICE_COUNT = 4
        const val MAX_QUESTION = 400
        const val MAX_CHOICE = 200
        const val MAX_EXPLANATION = 2000
    }
}

data class DraftChoice(val body: String, val isCorrect: Boolean)

/** 元の発表と食い違っていないか（AI の判定） */
data class Verdict(val consistent: Boolean, val reason: String)

/** Bedrock に送った・受け取ったトークンの数。費用をログで確かめる */
data class Usage(val calls: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0) {
    operator fun plus(other: Usage) =
        Usage(calls + other.calls, inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}
