package com.quizapp.quiz.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Clock

/**
 * 図の置き場所（S3）と、配る URL の作り方を組み立てる（ADR-0017）。
 *
 * どちらも起動のときには AWS に接続しない。
 */
@Configuration
@EnableConfigurationProperties(FigureProperties::class)
class FigureStorageConfiguration(private val properties: FigureProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun figureS3Client(): S3Client = S3Client.builder()
        .region(Region.of(properties.region))
        .credentialsProvider(credentials())
        .apply {
            endpoint()?.let { endpointOverride(it).forcePathStyle(true) }
        }
        .build()

    @Bean
    fun figureBucket(figureS3Client: S3Client): Bucket = S3Bucket(figureS3Client, properties.bucket)

    @Bean
    fun figureUrlSigner(): FigureUrlSigner {
        val cloudfront = properties.cloudfront
        if (cloudfront.url.isNotBlank()) {
            return CloudFrontFigureUrlSigner(
                cloudfront.url,
                cloudfront.keyPairId,
                cloudfront.privateKey,
                Clock.systemUTC(),
            )
        }
        log.info("図の URL は S3 の署名付き URL で作ります（CloudFront の設定がありません）")
        val publicEndpoint = properties.s3.publicEndpoint.ifBlank { properties.s3.endpoint }.ifBlank { null }
        val presigner = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials())
            .apply {
                publicEndpoint?.let {
                    endpointOverride(URI.create(it))
                    serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                }
            }
            .build()
        return S3PresignedFigureUrlSigner(presigner, properties.bucket, Clock.systemUTC())
    }

    private fun endpoint(): URI? = properties.s3.endpoint.ifBlank { null }?.let(URI::create)

    /**
     * 接続先を変えている（LocalStack）ときは、ダミーの認証情報を使う。LocalStack は中身を確かめない。
     * 手元の AWS の認証情報（SSO など）を、ローカルの S3 に送らない
     */
    private fun credentials(): AwsCredentialsProvider = if (endpoint() != null) {
        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
    } else {
        DefaultCredentialsProvider.builder().build()
    }
}
