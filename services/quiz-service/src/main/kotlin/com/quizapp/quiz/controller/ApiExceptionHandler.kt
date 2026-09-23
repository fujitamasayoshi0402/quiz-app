package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.CategoryNotFoundException
import com.quizapp.quiz.usecase.DifficultyNotFoundException
import com.quizapp.quiz.usecase.QuizNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * エラー応答を RFC 9457 の Problem Details で返す。
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CategoryNotFoundException::class)
    fun handleCategoryNotFound(e: CategoryNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたカテゴリは存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(QuizNotFoundException::class)
    fun handleQuizNotFound(e: QuizNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたクイズは存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(DifficultyNotFoundException::class)
    fun handleDifficultyNotFound(e: DifficultyNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された難易度は存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            title = "入力内容に誤りがあります"
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "入力が不正です") },
            )
        }

    /** ドメインの不変条件違反（`require` による検証）。 */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "入力が不正です").apply {
            title = "入力内容に誤りがあります"
        }

    /**
     * テナントが解決できないまま DB へアクセスした場合。
     * URL の slug が存在しないか、テナントを含まないパスから呼ばれている。
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ProblemDetail {
        log.warn("テナントの解決に失敗しました", e)
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたテナントは存在しません").apply {
            title = "リソースが見つかりません"
        }
    }

    /**
     * 行レベルセキュリティのポリシー違反は SQLState 42501 で返り、
     * Spring はこれを [BadSqlGrammarException] に分類する。
     * **「SQL 文法エラー」として利用者に見せない。**
     * 他テナントのリソースを指定した操作なので、存在しないものとして扱う。
     */
    @ExceptionHandler(BadSqlGrammarException::class)
    fun handleBadSqlGrammar(e: BadSqlGrammarException): ProblemDetail {
        val cause = e.rootCause?.message.orEmpty()
        if (cause.contains("row-level security")) {
            log.warn("テナント境界を越えた操作を拒否しました")
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたリソースは存在しません").apply {
                title = "リソースが見つかりません"
            }
        }
        log.error("SQL の実行に失敗しました", e)
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).apply {
            title = "サーバー内部エラー"
        }
    }
}
