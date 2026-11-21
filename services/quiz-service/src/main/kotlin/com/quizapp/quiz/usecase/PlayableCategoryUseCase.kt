package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.PlayableCategory
import org.springframework.stereotype.Service

/** 出題条件の選択肢を返す。 */
@Service
class PlayableCategoryUseCase(private val content: PlayableContentCache) {
    /** 出題の画面は、これだけを待って表示する。メモリから返し、DB が止まっていても待たせない（ADR-0038） */
    fun list(): List<PlayableCategory> = content.categories()
}
