package com.quizapp

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * quiz-service の起動クラス。
 *
 * `com.quizapp` 直下に置くことで、`quiz` と `answer` の両モジュールが
 * コンポーネントスキャンの対象になる。両者は将来の分離単位であり（ADR-0004）、
 * パッケージのトップレベルで分けている。
 */
@SpringBootApplication
class QuizServiceApplication

/** マイグレーションだけを流して終了するプロファイル（application-migrate.yml） */
const val MIGRATE_PROFILE = "migrate"

fun main(args: Array<String>) {
    val context = runApplication<QuizServiceApplication>(*args)
    // Flyway は起動の途中で走るため、ここに来た時点でマイグレーションは済んでいる。
    // 失敗したときは起動そのものが例外で止まり、終了コードが 0 以外になる。デプロイはそれを見て先へ進まない
    if (context.environment.matchesProfiles(MIGRATE_PROFILE)) {
        exitProcess(SpringApplication.exit(context))
    }
}
