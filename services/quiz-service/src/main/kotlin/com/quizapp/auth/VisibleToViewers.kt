package com.quizapp.auth

/**
 * 見学者（[TenantRole.VIEWER]）にも読ませる管理の API（ADR-0035）。GET のハンドラにだけ付ける。
 *
 * **付けていない管理の API は、見学者には 403 になる。** 管理の API を足したとき、付け忘れても見学者に見えない。
 * 付けるのは、デモのテナントの中身を見せてよいものだけ。人のメールアドレスなど、見に来た人に見せない情報を返すもの（招待の一覧）には付けない。
 * どの GET に付け、どれに付けないかは `ViewerAccessApiTest` が一覧で持ち、決めていない GET があると落ちる
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class VisibleToViewers
