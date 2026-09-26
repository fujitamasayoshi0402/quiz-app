package com.quizapp.answer.controller

import com.quizapp.answer.domain.RankingName
import com.quizapp.answer.domain.RankingPeriod
import com.quizapp.answer.usecase.Participation
import com.quizapp.answer.usecase.RankingUseCase
import com.quizapp.answer.usecase.RankingView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * テナント内のランキング（一般ユーザー向け）。
 *
 * 参加は本人の操作だけで変わる。**利用者を指定するパラメータを持たない。**
 */
@RestController
@RequestMapping("/api/t/{slug}/play/ranking", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "ランキング", description = "参加を選んだ人だけが載る。期間内に正解したクイズの数で並べる")
class RankingController(private val useCase: RankingUseCase) {

    @GetMapping
    @Operation(operationId = "getRanking", summary = "ランキングを取得")
    fun ranking(
        @Parameter(description = "`7d`（直近 7 日）/ `30d`（直近 30 日）/ `all`（全期間）")
        @RequestParam(defaultValue = "30d")
        period: String,
    ): RankingView = useCase.ranking(RankingPeriod.from(period))

    @PutMapping("/participation")
    @Operation(
        operationId = "participateInRanking",
        summary = "ランキングに参加する",
        description = "参加していれば名前を変える。同じテナントで使われている名前は 409",
    )
    fun participate(@Valid @RequestBody request: ParticipateRequest): Participation = useCase.participate(request.name)

    @DeleteMapping("/participation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "leaveRanking", summary = "ランキングへの参加をやめる")
    fun leave() = useCase.leave()
}

/** 前後の空白を落としたあとの長さは、ドメイン（[RankingName]）が確かめる */
data class ParticipateRequest(
    @field:NotBlank(message = "名前を入力してください")
    @field:Size(max = RankingName.MAX_LENGTH, message = "名前は {max} 文字以内で入力してください")
    val name: String,
)
