package com.quizapp.newsquiz

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.quizapp.newsquiz.infrastructure.BedrockQuizWriter
import com.quizapp.newsquiz.infrastructure.RssFeed
import com.quizapp.newsquiz.infrastructure.S3DraftStore
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.s3.S3Client
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

/**
 * Lambda の入口（`com.quizapp.newsquiz.Handler::handleRequest`）。EventBridge Scheduler が毎日呼ぶ（ADR-0031）。
 *
 * 環境変数（Terraform の modules/news-quiz-drafter が渡す）:
 *
 * | 名前         | 中身                                              |
 * | ------------ | ------------------------------------------------- |
 * | `FEED_URL`   | 読む RSS（AWS の What's New）                     |
 * | `MODEL_ID`   | Bedrock のモデル（推論プロファイル）              |
 * | `BUCKET`     | 受け箱と記録を置くバケット                        |
 * | `MAX_DRAFTS` | 1 回に作る下書きの上限（既定 3）                  |
 *
 * 入力が `{"check":true}` なら、初期化できることだけを確かめて終わる。Bedrock は呼ばない（デプロイの確認に使う）
 */
class Handler : RequestStreamHandler {
    private val store = S3DraftStore(S3Client.create(), env("BUCKET"))
    private val writerFactory = { BedrockQuizWriter(BedrockRuntimeClient.create(), env("MODEL_ID")) }
    private val feed = RssFeed(env("FEED_URL"))
    private val limits = Drafter.Limits(
        maxDrafts = System.getenv("MAX_DRAFTS")?.toIntOrNull() ?: Drafter.DEFAULT_MAX_DRAFTS,
    )

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val log: (String) -> Unit = { context.logger.log(it + "\n") }
        if (input.readAllBytes().decodeToString().contains("\"check\"")) {
            log("初期化できることを確かめました")
            return
        }
        // トークンの数は 1 回の起動ごとに数える。同じ実行環境が続けて呼ばれても、前の回の分を混ぜない
        Drafter(feed, writerFactory(), store, Clock.systemUTC(), log, limits).run()
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("環境変数 $name がありません")
}
