package com.quizapp.answer.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 間隔反復で「今日」を決める時計（DEV-178）。日の区切りは [com.quizapp.answer.domain.StudyDay] */
@Configuration
class StudyClockConfiguration {
    @Bean
    fun studyClock(): Clock = Clock.systemUTC()
}
