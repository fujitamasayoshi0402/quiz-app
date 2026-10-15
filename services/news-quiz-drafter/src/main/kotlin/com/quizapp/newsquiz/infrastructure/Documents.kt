package com.quizapp.newsquiz.infrastructure

import software.amazon.awssdk.core.document.Document

/** Bedrock の道具（tool）の JSON スキーマと入力を、Kotlin の値と行き来させる */
internal object Documents {
    fun of(value: Any?): Document = when (value) {
        null -> Document.fromNull()
        is String -> Document.fromString(value)
        is Boolean -> Document.fromBoolean(value)
        is Int -> Document.fromNumber(value)
        is Long -> Document.fromNumber(value)
        is Map<*, *> -> Document.fromMap(value.entries.associate { (k, v) -> k.toString() to of(v) })
        is List<*> -> Document.fromList(value.map(::of))
        else -> error("Document にできない値です: ${value::class}")
    }

    fun valueOf(document: Document): Any? = when {
        document.isNull -> null
        document.isString -> document.asString()
        document.isBoolean -> document.asBoolean()
        document.isNumber -> document.asNumber().toInt()
        document.isMap -> document.asMap().mapValues { valueOf(it.value) }
        document.isList -> document.asList().map(::valueOf)
        else -> null
    }
}
