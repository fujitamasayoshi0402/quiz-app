package com.quizapp.quiz.infrastructure

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 解説図の置き場所と、配る URL の作り方（ADR-0017）。
 *
 * 既定値はローカル（LocalStack）向け。AWS では、アプリのタスク定義の環境変数ですべて上書きする。
 * **AWS かどうかはプロファイルで分けない。** dev のプロファイルは、ローカルと AWS の両方で使う。
 * [cloudfront] の URL があれば CloudFront の署名付き URL、なければ S3 の署名付き URL を返す。
 */
@Validated
@ConfigurationProperties("app.figures")
data class FigureProperties(
    @field:NotBlank val bucket: String,
    @field:NotBlank val region: String,
    val s3: S3 = S3(),
    val cloudfront: CloudFront = CloudFront(),
) {
    data class S3(
        /** S3 の接続先。空なら AWS の S3。ローカルは LocalStack */
        val endpoint: String = "",
        /**
         * ブラウザに渡す署名付き URL の接続先。空なら [endpoint] と同じ。
         * docker compose では、アプリからは `localstack`、ブラウザからは `localhost` で届くため分ける
         */
        val publicEndpoint: String = "",
    )

    data class CloudFront(
        /** 図を配るドメインの URL（例: `https://figures.dev.example.com`）。この下に S3 のキーを続ける */
        val url: String = "",
        val keyPairId: String = "",
        /** 署名の秘密鍵（PKCS#8 の PEM）。AWS では ECS が SSM から読んで渡す */
        val privateKey: String = "",
    )
}
