package com.quizapp.notification.support

import com.quizapp.events.QuizEvent
import com.quizapp.notification.QuizEventReader
import java.nio.file.Files
import java.nio.file.Path

/** `docs/events/` の見本（ADR-0022）。送る側の quiz-service が、書き出すものと同じであることを確かめている */
object EventSamples {
    /** テストの作業ディレクトリはモジュール直下（services/notification-service） */
    val DIRECTORY: Path = Path.of("../../docs/events")

    /** ファイルの名前が `detail-type` */
    fun names(): List<String> = Files.list(DIRECTORY).use { files ->
        files.map {
            it.fileName.toString()
        }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted().toList()
    }

    fun json(detailType: String): String = Files.readString(DIRECTORY.resolve("$detailType.json"))

    fun event(detailType: String): QuizEvent = requireNotNull(QuizEventReader.readDetail(detailType, json(detailType)))
}
