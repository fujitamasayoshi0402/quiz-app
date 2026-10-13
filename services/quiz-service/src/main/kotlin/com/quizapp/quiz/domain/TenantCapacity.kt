package com.quizapp.quiz.domain

/** 数に上限があるもの（ADR-0028） */
enum class LimitedResource {
    /** クイズ。ゴミ箱のものも数える。復活で上限を超えないようにするため */
    QUIZZES,

    /** 解説図。消した図は数えない */
    FIGURES,
}

/** いくつ使っていて、いくつまで置けるか */
data class Usage(val used: Int, val limit: Int) {
    fun hasRoomFor(count: Int): Boolean = used + count <= limit
}

/**
 * テナントの数の上限（ADR-0028）。利用者が作ったテナントにだけある。運用者が作ったテナント（デモなど）は上限なし。
 *
 * **数えてから入れるため、同時に来た要求で数件超えることは許す。** 費用に上限を付けるのが目的で、厳密な数は求めない。
 * テナントの文脈（トランザクション）の中で呼ぶ
 */
interface TenantCapacity {
    /** 上限がなければ null */
    fun usage(resource: LimitedResource): Usage?

    /** [count] 個を足すと上限を超えるなら [TenantLimitReachedException] */
    fun requireRoomFor(resource: LimitedResource, count: Int = 1) {
        val usage = usage(resource) ?: return
        if (!usage.hasRoomFor(count)) throw TenantLimitReachedException(resource, usage.limit)
    }
}

class TenantLimitReachedException(val resource: LimitedResource, val limit: Int) :
    RuntimeException("テナントの上限に達しました: $resource（$limit）")
