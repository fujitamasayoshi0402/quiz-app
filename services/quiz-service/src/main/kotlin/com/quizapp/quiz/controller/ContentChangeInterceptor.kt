package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.PlayableContentCache
import com.quizapp.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 管理の API で何かを変えたら、そのテナントの出題の内容をメモリから消す（ADR-0038）。
 *
 * クイズ・カテゴリ・難易度・図を変える API は多く、パックの流し込み（ADR-0034）もここを通る。
 * 一つずつ消す処理を書くと、書き忘れた API で古い一覧が残る。**管理の API の変更は、成否も中身も問わずに消す。**
 * 消しすぎても、次に読むときに DB を 1 回引くだけで済む。
 *
 * 消すのは要求の終わり。変更のトランザクションは、そのときにはもう終わっている
 */
@Component
class ContentChangeInterceptor(private val content: PlayableContentCache) : HandlerInterceptor {

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        if (request.method in READS) return
        TenantContext.get()?.let(content::forget)
    }

    private companion object {
        val READS = setOf("GET", "HEAD", "OPTIONS")
    }
}

@Configuration
class ContentChangeConfigurer(private val interceptor: ContentChangeInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/t/*/admin/**")
    }
}
