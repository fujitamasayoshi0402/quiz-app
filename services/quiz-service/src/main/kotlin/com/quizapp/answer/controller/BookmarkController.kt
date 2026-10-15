package com.quizapp.answer.controller

import com.quizapp.answer.usecase.BookmarkList
import com.quizapp.answer.usecase.BookmarkUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 「あとで見直す」印（一般ユーザー向け）。**扱えるのは自分の印だけ。** 利用者を指定するパラメータを持たない。
 *
 * 付けるのも外すのも、何度呼んでも同じ結果になる（PUT と DELETE）。
 * 画面で続けて押されたり、通信をやり直したりしても、印がずれない。
 */
@RestController
@RequestMapping("/api/t/{slug}/play/bookmarks", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "あとで見直す", description = "問題に付ける印。印の付いた問題だけを出題できる（出題対象 `bookmarked`）")
class BookmarkController(private val useCase: BookmarkUseCase) {

    @GetMapping
    @Operation(
        operationId = "listBookmarks",
        summary = "印を付けたクイズ",
        description = "いま出題できるクイズだけを、カテゴリと難易度とともに返す。削除・非公開になったクイズへの印は出さない",
    )
    fun list(): BookmarkList = useCase.list()

    @PutMapping("/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "addBookmark",
        summary = "印を付ける",
        description = "付いていれば何もしない。いま出題できないクイズは 404",
    )
    fun add(@PathVariable quizId: UUID) = useCase.add(quizId)

    @DeleteMapping("/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeBookmark", summary = "印を外す", description = "付いていなければ何もしない")
    fun remove(@PathVariable quizId: UUID) = useCase.remove(quizId)
}
