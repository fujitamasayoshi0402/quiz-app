package com.quizapp.support

import com.quizapp.support.fake.InMemorySlackWebhookStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * テストでは SSM（LocalStack）につながない。Webhook の URL はメモリに置く。
 * パラメータの名前の組み立ては、`SsmSlackWebhookStoreTest` が確かめる。
 */
@Configuration
class TestNotificationConfiguration {

    @Bean
    @Primary
    fun testSlackWebhookStore(): InMemorySlackWebhookStore = InMemorySlackWebhookStore()
}
