package com.quizapp.support

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * 1 回の操作で、DB に何本の SQL を送ったかを数える（DEV-112）。
 *
 * 件数に比例して SQL が増える読み方（N+1）は、テストのデータが少ないと速さに表れず、負荷試験まで気づけない。
 * 本数そのものを確かめる。
 *
 * 数えるのは、[count] を呼んだスレッドで用意した SQL だけ。MockMvc は要求を同じスレッドで処理する。
 * 別のスレッドで動くもの（Outbox の送信）は混ざらない。
 */
object SqlStatementCounter {

    private val counter = ThreadLocal<Int?>()

    /** [block] の間に用意した SQL の本数を返す */
    fun count(block: () -> Unit): Int {
        counter.set(0)
        try {
            block()
            return counter.get() ?: 0
        } finally {
            counter.remove()
        }
    }

    internal fun record() {
        counter.get()?.let { counter.set(it + 1) }
    }
}

/** アプリの DataSource を包み、接続で SQL を用意するたびに [SqlStatementCounter] に数えさせる */
@Configuration
class SqlStatementCounterConfiguration {

    @Bean
    fun sqlStatementCountingPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && beanName == "dataSource") counting(bean) else bean
    }

    private fun counting(dataSource: DataSource): DataSource = proxy(dataSource) { method, result ->
        if (method.name == "getConnection") counting(result as Connection) else result
    }

    private fun counting(connection: Connection): Connection = proxy(connection) { method, result ->
        if (method.name in STATEMENT_METHODS) SqlStatementCounter.record()
        result
    }

    private inline fun <reified T : Any> proxy(target: T, crossinline after: (Method, Any?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val result = try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            after(method, result)
        } as T

    private companion object {
        val STATEMENT_METHODS = setOf("prepareStatement", "prepareCall", "createStatement")
    }
}
