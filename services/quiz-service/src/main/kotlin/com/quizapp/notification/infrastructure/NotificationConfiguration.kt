package com.quizapp.notification.infrastructure

import com.quizapp.notification.domain.SlackWebhookStore
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import java.net.URI

/** 通知先の置き場所（SSM）を組み立てる。起動のときには AWS に接続しない */
@Configuration
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationConfiguration(private val properties: NotificationProperties) {

    @Bean
    fun slackWebhookStore(): SlackWebhookStore = SsmSlackWebhookStore(ssmClient(), properties.parameterPrefix)

    /**
     * 接続先を変えている（LocalStack）ときは、ダミーの認証情報を使う。
     * 手元の AWS の認証情報（SSO など）を、ローカルの SSM に送らない
     */
    private fun ssmClient(): SsmClient {
        val endpoint = properties.ssm.endpoint.ifBlank { null }?.let(URI::create)
        return SsmClient.builder()
            .region(Region.of(properties.region))
            .apply {
                if (endpoint != null) {
                    endpointOverride(endpoint)
                    credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                } else {
                    credentialsProvider(DefaultCredentialsProvider.builder().build())
                }
            }
            .build()
    }
}
