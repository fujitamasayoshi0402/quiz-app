package com.quizapp.quiz.infrastructure.newsquiz

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 技術ニュースから AI が作った下書きの取り込み（ADR-0031）。**既定では止めている。** dev だけで、タスク定義の環境変数で動かす
 */
@ConfigurationProperties("app.news-quizzes")
data class NewsQuizProperties(
    val enabled: Boolean = false,
    /** Lambda（news-quiz-drafter）が下書きを置くバケット。キーは `inbox/` の下 */
    val bucket: String = "",
    /** 入れる先のテナントとカテゴリと難易度。名前で指し、無ければ取り込まない（シードが作る） */
    val tenantSlug: String = "demo",
    val category: String = "AWS の最新情報",
    val difficulty: String = "ニュース",
    /** 元にした発表の URL は、これで始まるものだけを受け付ける。読む情報源（AWS の What's New）の外を出典にしない */
    val sourcePrefix: String = "https://aws.amazon.com/",
    /** 確かめていない AI の下書きがこの数あれば、取り込まない。受け箱にたまり、Lambda も作るのをやめる */
    val maxPendingDrafts: Int = DEFAULT_MAX_PENDING_DRAFTS,
    /** 取り込むかどうかを見る間隔。受け箱を見るのは [every] に 1 回 */
    val interval: Duration = Duration.ofMinutes(1),
    val every: Duration = Duration.ofHours(1),
    /** 利用者の要求で DB を使ってから、見てよい時間。Aurora が一時停止するまで（dev は 30 分）より短くする */
    val activeWindow: Duration = Duration.ofMinutes(DEFAULT_ACTIVE_WINDOW_MINUTES),
) {
    private companion object {
        const val DEFAULT_MAX_PENDING_DRAFTS = 30
        const val DEFAULT_ACTIVE_WINDOW_MINUTES = 5L
    }
}
