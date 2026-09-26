package com.quizapp.support

import com.quizapp.quiz.infrastructure.Bucket
import com.quizapp.support.fake.InMemoryBucket
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * テストでは S3（LocalStack）につながない。図の本体はメモリに置く。
 *
 * 差し替えるのはオブジェクトの置き場所だけ。キーの組み立てと、URL の署名（S3 の署名付き URL）は本物を通す。
 * `@TestConfiguration` にしないのは、[TestAuthConfiguration] と同じくコンポーネントスキャンで拾わせるため。
 */
@Configuration
class TestFigureConfiguration {

    @Bean
    @Primary
    fun testFigureBucket(): InMemoryBucket = InMemoryBucket()
}
