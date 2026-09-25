package com.quizapp.support

import com.quizapp.quiz.controller.CategoryResponse
import com.quizapp.quiz.controller.DifficultyResponse
import com.quizapp.quiz.controller.QuizResponse
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 挑戦のテストで使うクイズを、管理 API 経由で用意する。
 *
 * SQL を直接流し込まず API を通すのは、**出題側が管理側の書き込んだ形をそのまま読めること**も
 * あわせて確認したいため。複合外部キーやトリガーの挙動を迂回しない。
 */
class PlayFixture(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val slug: String,
    /** 管理 API は管理者として所属していないと叩けない */
    private val admin: UUID = TestAuth.ADMIN,
) {
    fun category(name: String): UUID {
        val result = mockMvc.post("/api/t/$slug/admin/categories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
            header("Authorization", TestAuth.bearer(admin))
        }.andExpect { }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    fun difficulty(categoryId: UUID, name: String, level: Int): UUID {
        val result = mockMvc.post("/api/t/$slug/admin/categories/$categoryId/difficulties") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level}"""
            header("Authorization", TestAuth.bearer(admin))
        }.andExpect { }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, DifficultyResponse::class.java).id
    }

    /** 先頭の選択肢を正解にする。テストはこれを前提に「1 番目が正解」として書ける。 */
    fun quiz(categoryId: UUID, difficultyId: UUID, question: String, status: String = "published"): UUID {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        val result = mockMvc.post("/api/t/$slug/admin/quizzes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$categoryId","difficultyId":"$difficultyId",
                 "question":"$question","explanation":"$question の解説",
                 "choices":[$choices],"status":"$status"}
            """.trimIndent()
            header("Authorization", TestAuth.bearer(admin))
        }.andExpect { }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, QuizResponse::class.java).id
    }
}
