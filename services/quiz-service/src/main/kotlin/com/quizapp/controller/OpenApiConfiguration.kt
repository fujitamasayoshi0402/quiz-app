package com.quizapp.controller

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 定義のメタ情報。
 *
 * 定義はコードから生成し、`docs/api/openapi.yaml` に固定して持つ（ADR-0010）。
 * 実装との乖離は `OpenApiSnapshotTest` が検出する。
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun quizAppOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("quiz-app API")
                .version(API_VERSION)
                .description(
                    """
                    カテゴリと難易度を自由に定義できるクイズアプリの API。

                    テナント配下のエンドポイントは、触れる人によってパスが分かれる。

                    - `/api/t/{slug}/admin/...` … 管理者として所属していること
                    - `/api/t/{slug}/play/...` … 所属していること

                    同じクイズでも管理 API は正解を含み、出題 API は含まない。
                    """.trimIndent(),
                ),
        )
        // 既定では起動したホストが書き込まれ、生成のたびに変わる。スナップショットを安定させるため固定する
        .servers(listOf(Server().url("/").description("アプリケーションと同じオリジン")))
        .components(
            Components()
                .addSecuritySchemes(
                    STUB_AUTH,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-User-Id")
                        .description(
                            "Phase 1 のスタブ認証。利用者の UUID をそのまま渡す。" +
                                "Phase 3 で Cognito の JWT に差し替える",
                        ),
                ),
        )
        .addSecurityItem(SecurityRequirement().addList(STUB_AUTH))

    /**
     * テナント配下の全エンドポイントに共通のエラー応答を足す。
     *
     * 認可は [com.quizapp.auth.TenantAccessInterceptor] がパスで判定するため、
     * **この 3 つはどのエンドポイントでも起こりうる。**
     * 個々のメソッドに注釈で書くと、書き忘れたところだけ定義から漏れる。
     *
     * スキーマの登録もここで行う。**`OpenAPI` の Bean に載せても消える。**
     * springdoc は走査した結果で `components.schemas` を置き換えるため、
     * 参照だけが残った壊れた定義になる。
     */
    @Bean
    fun tenantScopedErrorResponses(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.components.addSchemas(PROBLEM_DETAIL, problemDetailSchema())

        val problem = Content().addMediaType(
            PROBLEM_JSON,
            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_DETAIL")),
        )
        openApi.paths
            .filterKeys { it.startsWith(TENANT_SCOPED_PREFIX) }
            .values
            .flatMap { it.readOperations() }
            .forEach { operation ->
                COMMON_ERRORS.forEach { (code, description) ->
                    operation.responses.addApiResponse(
                        code,
                        ApiResponse().description(description).content(problem),
                    )
                }
            }
    }

    /** RFC 9457 の Problem Details。例外ハンドラは走査されないので、ここで形を宣言する。 */
    private fun problemDetailSchema(): Schema<*> = ObjectSchema()
        .description("RFC 9457 の Problem Details 形式のエラー応答")
        .addProperty("type", StringSchema().format("uri"))
        .addProperty("title", StringSchema().description("エラーの種類を表す短い説明"))
        .addProperty("status", IntegerSchema().description("HTTP ステータスコード"))
        .addProperty("detail", StringSchema().description("この発生に固有の説明"))
        .addProperty("instance", StringSchema().format("uri"))
        .additionalProperties(true)

    private companion object {
        /** API の互換性を表す。破壊的な変更を入れたときに上げる */
        const val API_VERSION = "0.1.0"
        const val STUB_AUTH = "stubUser"
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val PROBLEM_JSON = "application/problem+json"
        const val TENANT_SCOPED_PREFIX = "/api/t/"

        val COMMON_ERRORS = listOf(
            "401" to "利用者を特定できない",
            "403" to "所属はしているが、この操作に必要な権限がない",
            "404" to "テナントが存在しない、または所属していない",
        )
    }
}
