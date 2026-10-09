package com.quizapp.controller

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component
import kotlin.reflect.full.primaryConstructor

/**
 * Kotlin のクラスから、必須の項目を OpenAPI 定義に書き込む。
 *
 * springdoc は Kotlin の null 許容は読むが、必須かどうかは読まない。
 * そのままでは応答のすべての項目が省略可能として定義され、生成したクライアントの型が
 * `string | undefined` だらけになる。**実際には必ず入っている値を、使う側が毎回疑うことになる。**
 *
 * 必須とみなすのは、主コンストラクタの引数のうち **null を許さず、既定値もないもの**。
 * 既定値があるリクエストの項目（`sortOrder: Int = 0` など）は、省略できるので必須にしない。
 * 代償として、既定値を持つ**応答**の項目（`Trash` の各一覧など）も省略可能として定義される。
 * 実際には常に入っているが、定義上は安全側に倒れるだけなので許容する。
 */
@Component
class KotlinRequiredPropertyConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>,
    ): Schema<*>? {
        val schema = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        // クラスの定義は components に登録され、戻り値は `$ref` だけのことが多い。登録された側に書き込む
        val modelName = schema?.name ?: schema?.`$ref`?.substringAfterLast('/')
        val target = modelName?.let { context.definedModels[it] } ?: schema
        val properties = target?.properties?.keys.orEmpty()
        (requiredProperties(type) intersect properties).sorted().forEach { property ->
            if (target?.required?.contains(property) != true) target?.addRequiredItem(property)
        }
        return schema
    }

    private fun requiredProperties(type: AnnotatedType): Set<String> {
        // swagger-core は型を Jackson 2 の JavaType で渡してくることがある。swagger 自身のマッパーで解決する
        val raw = runCatching { Json.mapper().constructType(type.type).rawClass }.getOrNull()
        val constructor = raw
            ?.takeIf { it.isAnnotationPresent(Metadata::class.java) }
            ?.let { runCatching { it.kotlin.primaryConstructor }.getOrNull() }
        return constructor?.parameters.orEmpty()
            .filter { !it.type.isMarkedNullable && !it.isOptional }
            .mapNotNull { it.name }
            .toSet()
    }
}
