package com.quizapp.controller

import com.quizapp.answer.domain.DuplicateAnswerException
import com.quizapp.answer.usecase.AttemptAlreadyFinishedException
import com.quizapp.answer.usecase.AttemptInProgressException
import com.quizapp.answer.usecase.AttemptNotFoundException
import com.quizapp.answer.usecase.InvalidChoiceException
import com.quizapp.answer.usecase.NoQuizAvailableException
import com.quizapp.answer.usecase.QuizNoLongerAvailableException
import com.quizapp.answer.usecase.QuizNotInAttemptException
import com.quizapp.auth.AdminRoleRequiredException
import com.quizapp.auth.TenantAccessDeniedException
import com.quizapp.auth.UserNotIdentifiedException
import com.quizapp.quiz.domain.DeletedItemNotFound
import com.quizapp.quiz.domain.RestoreBlocked
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
 *
 * 例外そのものを使わないハンドラは引数を取らない。
 * `@ExceptionHandler` に型を書いてあれば Spring は引数なしのメソッドを呼べる。
 * 使わない引数を並べると、**どのハンドラが例外の中身を見ているのかが読み取れなくなる。**
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CategoryNotFoundException::class)
    fun handleCategoryNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたカテゴリは存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(QuizNotFoundException::class)
    fun handleQuizNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたクイズは存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(DifficultyNotFoundException::class)
    fun handleDifficultyNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された難易度は存在しません").apply {
            title = "リソースが見つかりません"
        }

    /**
     * 中断中の挑戦がある状態で新しく始めようとした。
     *
     * 黙って破棄せず、再開するか破棄するかを選ばせる。
     * 解きかけの記録が予告なく消えないようにするため、件数を応答に含める。
     */
    @ExceptionHandler(AttemptInProgressException::class)
    fun handleAttemptInProgress(e: AttemptInProgressException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "中断中のクイズがあります").apply {
            title = "中断中の挑戦があります"
            setProperty("attemptId", e.summary.id)
            setProperty("totalCount", e.summary.totalCount)
            setProperty("answeredCount", e.summary.answeredCount)
        }

    @ExceptionHandler(AttemptNotFoundException::class)
    fun handleAttemptNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された挑戦は存在しません").apply {
            title = "リソースが見つかりません"
        }

    @ExceptionHandler(AttemptAlreadyFinishedException::class)
    fun handleAttemptFinished(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "この挑戦はすでに終了しています").apply {
            title = "操作できない状態です"
        }

    /**
     * 条件に合うクイズが 1 件もない。
     *
     * 404 にしない。テナントや URL の誤りと区別できず、画面の出し分けができなくなる。
     */
    @ExceptionHandler(NoQuizAvailableException::class)
    fun handleNoQuiz(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "条件に合うクイズがありません。条件を変えてください",
    ).apply { title = "出題できるクイズがありません" }

    @ExceptionHandler(QuizNotInAttemptException::class, InvalidChoiceException::class)
    fun handleInvalidAnswer(e: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "回答の内容が不正です").apply {
            title = "入力内容に誤りがあります"
        }

    /** 出題後にクイズが削除・非公開になった。 */
    @ExceptionHandler(QuizNoLongerAvailableException::class)
    fun handleQuizGone(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "このクイズは出題できなくなりました").apply {
            title = "クイズが変更されました"
        }

    @ExceptionHandler(DuplicateAnswerException::class)
    fun handleDuplicateAnswer(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "このクイズにはすでに回答しています").apply {
            title = "操作できない状態です"
        }

    /** アクセストークンが無いか、受け付けられなかった（署名、期限、発行先）。 */
    @ExceptionHandler(UserNotIdentifiedException::class)
    fun handleUserNotIdentified(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "利用者を特定できません").apply {
            title = "認証が必要です"
        }

    /**
     * 所属していないテナントへのアクセス。
     *
     * **403 ではなく 404 を返す。** 権限エラーとして区別すると、
     * その slug のテナントが実在することが分かってしまう。
     */
    @ExceptionHandler(TenantAccessDeniedException::class)
    fun handleTenantAccessDenied(): ProblemDetail {
        log.warn("所属していないテナントへのアクセスを拒否しました")
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定されたテナントは存在しません").apply {
            title = "リソースが見つかりません"
        }
    }

    /** 所属はしているが管理者ではない。テナントの存在は既知なので 403 で返す。 */
    @ExceptionHandler(AdminRoleRequiredException::class)
    fun handleAdminRequired(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "この操作には管理者の権限が必要です").apply {
            title = "権限がありません"
        }

    @ExceptionHandler(DeletedItemNotFound::class)
    fun handleDeletedItemNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "削除済みの項目が見つかりません").apply {
            title = "リソースが見つかりません"
        }

    /**
     * 復活できない状態。
     *
     * **理由を `obstacle` で返す。** 親が削除済みなのか名前が埋まっているのかで、
     * 利用者が次に取る操作が変わる（親を先に戻す / 既存のものを消すか改名する）。
     */
    @ExceptionHandler(RestoreBlocked::class)
    fun handleRestoreBlocked(e: RestoreBlocked): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "復活できません").apply {
            title = "復活できません"
            setProperty("obstacle", e.obstacle.name.lowercase())
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
