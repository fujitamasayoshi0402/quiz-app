package com.quizapp.support

import com.quizapp.support.fake.InMemoryEventBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * テストでは EventBridge（LocalStack）につながない。送ったイベントはメモリに残す。
 * EventBridge へ実際に送れることは、`EventBridgeEventBusLocalStackTest` が確かめる。
 */
@Configuration
class TestEventsConfiguration {

    @Bean
    @Primary
    fun testEventBus(): InMemoryEventBus = InMemoryEventBus()
}
