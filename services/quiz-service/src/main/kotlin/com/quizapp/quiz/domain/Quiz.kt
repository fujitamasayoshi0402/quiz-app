package com.quizapp.quiz.domain

import java.util.UUID

enum class QuizStatus {
    /** 作りかけ。出題されない。不完全な状態でも保存できる */
    DRAFT,

    /** 公開済み。出題の対象になる */
    PUBLISHED,
    ;

    companion object {
        fun from(value: String): QuizStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("状態は draft または published を指定してください: $value")
    }
}

/**
 * クイズの選択肢。クイズに完全に従属するため、単体では意味を持たない。
 *
 * [id] を持つのは、出題したあとに「どれを選んだか」を受け取る必要があるため。
 * 新規作成時は null で、永続化されると採番される。
 */
data class Choice(val id: UUID? = null, val body: String, val isCorrect: Boolean) {
    init {
        require(body.isNotBlank()) { "選択肢の本文を入力してください" }
        require(body.length <= MAX_BODY_LENGTH) { "選択肢は $MAX_BODY_LENGTH 文字以内で入力してください" }
    }

    companion object {
        const val MAX_BODY_LENGTH = 500
    }
}

/**
 * クイズ。選択肢を含めて 1 つの集約として扱う。
 *
 * **「選択肢は 2〜6 個」「正解は 1 個以上」は公開時の不変条件**とする（DEV-243）。
 * 下書きは作りかけを保存する場所なので、選択肢が揃っていなくても保存できる。
 * この 2 つが揃っていないクイズは出題も採点も成立しないため、公開させない。
 *
 * 行数に関する制約は DB では表現できない（`CHECK` は行をまたげない）ため、ここで守る。
 */
data class Quiz(
    val id: UUID? = null,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    val explanation: String,
    val choices: List<Choice> = emptyList(),
    val status: QuizStatus = QuizStatus.DRAFT,
    /**
     * AI が技術ニュースから作ったときの、元にした発表の URL（ADR-0031）。人が作ったものは null。
     * 作るときにだけ決まり、編集しても変わらない
     */
    val sourceUrl: String? = null,
) {
    init {
        require(question.isNotBlank()) { "問題文を入力してください" }
        require(question.length <= MAX_QUESTION_LENGTH) { "問題文は $MAX_QUESTION_LENGTH 文字以内で入力してください" }
        require(choices.size <= MAX_CHOICE_COUNT) { "選択肢は $MAX_CHOICE_COUNT 個までです" }
        require(sourceUrl == null || (sourceUrl.startsWith("https://") && sourceUrl.length <= MAX_SOURCE_URL_LENGTH)) {
            "出典の URL は https:// で始まる $MAX_SOURCE_URL_LENGTH 文字以内にしてください"
        }

        if (status == QuizStatus.PUBLISHED) {
            require(choices.size in MIN_CHOICE_COUNT..MAX_CHOICE_COUNT) {
                "公開するには選択肢が $MIN_CHOICE_COUNT〜$MAX_CHOICE_COUNT 個必要です"
            }
            require(choices.any { it.isCorrect }) { "公開するには正解を 1 つ以上指定してください" }
            require(explanation.isNotBlank()) { "公開するには解説を入力してください" }
        }
    }

    /** 問題文と解説が指している図の ID（ADR-0020、DEV-242）。保存する前に、テナントにあるかを確かめる */
    fun figureIds(): Set<UUID> = FigureReferences.findIn(question) + FigureReferences.findIn(explanation)

    companion object {
        const val MIN_CHOICE_COUNT = 2
        const val MAX_CHOICE_COUNT = 6

        /** AI が技術ニュースから作る下書きの選択肢の数（ADR-0031）。人が作るクイズは 2〜6 個から選べる（DEV-243） */
        const val CHOICE_COUNT = 4

        const val MAX_QUESTION_LENGTH = 2000
        const val MAX_SOURCE_URL_LENGTH = 2048

        /** 問題文の書き方。API 定義の説明に使う。表示する側が守ること（ADR-0018、ADR-0020、ADR-0021、DEV-242） */
        const val QUESTION_FORMAT = "問題文。Markdown（GitHub Flavored Markdown）で書く。" +
            "改行はそのまま改行として表示する。生の HTML は表示しない。" +
            "画像は `![代替テキスト](figure:<図の ID>)` で解説図を指したものだけを表示し、" +
            "`[文字](figure:<図の ID>)` は解説図を新しいタブで開くリンクにする。" +
            "本文の中に出す画像は GET /api/t/{slug}/play/figures/{id}/preview から取る（PDF は 1 ページ目の画像になる）。" +
            "図を開くときは GET /api/t/{slug}/play/figures/{id} を使う。保存するとき、指している図がテナントにあることを確かめる"

        /** 解説の書き方。API 定義の説明に使う。表示する側が守ること（ADR-0018、ADR-0020、ADR-0021） */
        const val EXPLANATION_FORMAT = "解説。Markdown（GitHub Flavored Markdown）で書く。" +
            "改行はそのまま改行として表示する。生の HTML は表示しない。" +
            "画像は `![代替テキスト](figure:<図の ID>)` で解説図を指したものだけを表示し、" +
            "`[文字](figure:<図の ID>)` は解説図を新しいタブで開くリンクにする。" +
            "本文の中に出す画像は GET /api/t/{slug}/play/figures/{id}/preview から取る（PDF は 1 ページ目の画像になる）。" +
            "図を開くときは GET /api/t/{slug}/play/figures/{id} を使う。保存するとき、指している図がテナントにあることを確かめる"

        /** 問題文や解説が、テナントにない図を指しているときの理由。どの ID かは返さない（送った側が本文から分かる） */
        const val FIGURE_NOT_FOUND = "解説が指している図が見つかりません"
    }
}
