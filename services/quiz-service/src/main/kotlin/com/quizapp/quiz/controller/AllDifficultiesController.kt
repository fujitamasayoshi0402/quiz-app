package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.DifficultyUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * カテゴリをまたいだ難易度の一覧（DEV-144）。
 *
 * 難易度の操作はカテゴリの下に置く（[DifficultyController]）が、クイズの一覧や編集の画面は、すべてのカテゴリの難易度を使う。
 * カテゴリの数だけ問い合わせると、要求がまとめて出て、利用者ごとの流量の上限に当たる
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/difficulties", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "難易度", description = "カテゴリ配下の難易度を CRUD する。体系はカテゴリごとに決める")
class AllDifficultiesController(private val useCase: DifficultyUseCase) {

    @GetMapping
    @Operation(
        operationId = "listAllDifficulties",
        summary = "すべてのカテゴリの難易度の一覧",
        description = "カテゴリの並び順、レベル、並び順で返す。削除したカテゴリの下にあるものは含まない",
    )
    fun list(): List<DifficultyResponse> = useCase.listAll().map(DifficultyResponse::from)
}
