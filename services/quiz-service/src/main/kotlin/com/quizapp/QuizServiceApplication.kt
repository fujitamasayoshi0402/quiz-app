package com.quizapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * quiz-service の起動クラス。
 *
 * `com.quizapp` 直下に置くことで、`quiz` と `answer` の両モジュールが
 * コンポーネントスキャンの対象になる。両者は将来の分離単位であり（ADR-0004）、
 * パッケージのトップレベルで分けている。
 */
@SpringBootApplication
class QuizServiceApplication

fun main(args: Array<String>) {
    runApplication<QuizServiceApplication>(*args)
}
