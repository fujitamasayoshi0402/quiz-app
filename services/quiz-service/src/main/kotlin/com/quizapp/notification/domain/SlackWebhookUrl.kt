package com.quizapp.notification.domain

/**
 * テナントの通知先になる、Slack の Incoming Webhook の URL（ADR-0022）。
 *
 * **`https://hooks.slack.com/` の下だけを受け付ける。** 任意の URL を許すと、管理者が指定した先へ
 * notification-service（Lambda）が要求を送る踏み台になる。
 * 使える文字も、Slack が発行する URL に現れるもの（英数字、`/`、`_`、`-`）に絞る。
 * `@`、`%`、`?`、`.` を通さないので、ホストを書き換えたり、別の場所を指したりする余地が残らない。
 *
 * **URL は secret として扱う。** 知っていれば、誰でもそのチャンネルに投稿できる。
 * [toString] は中身を出さない。ログや例外のメッセージに紛れ込ませない。
 */
class SlackWebhookUrl(val value: String) {

    init {
        require(value.length <= MAX_LENGTH && Regex(PATTERN).matches(value)) {
            "Slack の Incoming Webhook の URL ではありません"
        }
    }

    override fun toString() = "SlackWebhookUrl(***)"

    companion object {
        /** 入力の検証（`@Pattern`）でも使う。定義に表れ、画面のスキーマにも入る */
        const val PATTERN = "^https://hooks\\.slack\\.com/[A-Za-z0-9/_-]+$"

        /** Slack が発行する URL は 80 文字ほど。大きな値を SSM に置かせない */
        const val MAX_LENGTH = 500
    }
}
