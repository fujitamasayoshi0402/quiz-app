package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 出題条件の選択肢。**一般ユーザーに見せてよいものだけが返ること**を確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlayCategoryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant

    private lateinit var fixture: PlayFixture

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin().join(TestAuth.MEMBER)
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
    }

    @Test
    @DisplayName("公開済みのクイズの数を、難易度ごとと、カテゴリの合計で返す")
    fun countsPublishedQuizzes() {
        val aws = fixture.category("AWS")
        val saa = fixture.difficulty(aws, "SAA", 2)
        val sap = fixture.difficulty(aws, "SAP", 3)
        fixture.quiz(aws, saa, "問題 1")
        fixture.quiz(aws, saa, "問題 2")
        fixture.quiz(aws, sap, "問題 3")

        list().andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("AWS") }
            jsonPath("$[0].quizCount") { value(3) }
            jsonPath("$[0].difficulties[0].name") { value("SAA") }
            jsonPath("$[0].difficulties[0].level") { value(2) }
            jsonPath("$[0].difficulties[0].quizCount") { value(2) }
            jsonPath("$[0].difficulties[1].name") { value("SAP") }
            jsonPath("$[0].difficulties[1].quizCount") { value(1) }
        }
    }

    @Test
    @DisplayName("下書きしかない難易度とカテゴリは返さない")
    fun hidesDraftOnly() {
        val aws = fixture.category("AWS")
        fixture.quiz(aws, fixture.difficulty(aws, "SAA", 2), "公開済み")
        fixture.quiz(aws, fixture.difficulty(aws, "SAP", 3), "下書き", status = "draft")
        val auth = fixture.category("認証認可")
        fixture.quiz(auth, fixture.difficulty(auth, "基礎", 1), "下書き", status = "draft")

        // 下書きの存在は、件数からも名前からも分からないこと
        list().andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].quizCount") { value(1) }
            jsonPath("$[0].difficulties.length()") { value(1) }
            jsonPath("$[0].difficulties[0].name") { value("SAA") }
        }
    }

    @Test
    @DisplayName("クイズのないカテゴリ・難易度と、削除したクイズは返さない")
    fun hidesEmptyAndDeleted() {
        val aws = fixture.category("AWS")
        fixture.difficulty(aws, "SAA", 2)
        val auth = fixture.category("認証認可")
        val deleted = fixture.quiz(auth, fixture.difficulty(auth, "基礎", 1), "削除する問題")
        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$deleted") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        list().andExpect { jsonPath("$.length()") { value(0) } }
    }

    @Test
    @DisplayName("代表の図は、易しい難易度から登録順に見て、最初に図を指している問題の最初の図")
    fun picksCoverFigureFromEasiestQuiz() {
        val science = fixture.category("身近な科学")
        val easy = fixture.difficulty(science, "やさしい", 1)
        val hard = fixture.difficulty(science, "むずかしい", 3)
        val hardFigure = fixture.figure()
        val first = fixture.figure()
        val second = fixture.figure()
        // 難しい問題を先に登録しても、易しい難易度の問題が優先される
        fixture.quiz(science, hard, "難しい問題", explanation = "![図](figure:$hardFigure)")
        fixture.quiz(science, easy, "図のない問題")
        fixture.quiz(science, easy, "図のある問題", explanation = "![1](figure:$first)\n\n![2](figure:$second)")
        fixture.quiz(science, easy, "後から登録した問題", explanation = "![図](figure:$second)")

        list().andExpect { jsonPath("$[0].coverFigureId") { value(first.toString()) } }
    }

    @Test
    @DisplayName("下書きの問題の図は代表にしない。図を使う公開済みの問題がなければ null")
    fun skipsDraftFigures() {
        val science = fixture.category("身近な科学")
        val easy = fixture.difficulty(science, "やさしい", 1)
        fixture.quiz(science, easy, "下書き", status = "draft", explanation = "![図](figure:${fixture.figure()})")
        fixture.quiz(science, easy, "図のない問題")

        list().andExpect {
            jsonPath("$[0].name") { value("身近な科学") }
            jsonPath("$[0].coverFigureId") { doesNotExist() }
        }
    }

    private fun list() = mockMvc.get("/api/t/${tenant.slug}/play/categories") {
        // 一般ユーザーが使う API なので、管理者ではない利用者で呼ぶ
        header("Authorization", TestAuth.bearer(TestAuth.MEMBER))
    }
}
