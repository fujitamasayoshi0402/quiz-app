package com.quizapp.quiz.infrastructure

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * ブラウザが図を取りに行く、期限つきの URL を作る（ADR-0017）。
 *
 * **期限は 5〜10 分。** 5 分の区切りの終わりから 5 分後を期限にする。
 * 同じ区切りの間は同じ期限になり、CloudFront では URL も同じになる。ブラウザのキャッシュが効く。
 */
interface FigureUrlSigner {
    fun sign(key: String): URI

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(5)

        fun expiresAt(now: Instant): Instant {
            val window = WINDOW.seconds
            val windowEnd = Instant.ofEpochSecond((now.epochSecond / window + 1) * window)
            return windowEnd.plus(WINDOW)
        }
    }
}

/**
 * CloudFront の署名付き URL（固定のポリシー）。AWS で使う。
 *
 * 署名そのものは SDK に任せる。CloudFront は、キーグループに登録した公開鍵で署名を確かめ、
 * 署名のない要求と期限の切れた要求を 403 で返す。
 */
class CloudFrontFigureUrlSigner(
    private val baseUrl: String,
    private val keyPairId: String,
    privateKeyPem: String,
    private val clock: Clock,
) : FigureUrlSigner {

    /** 起動のときに読む。鍵が壊れていれば、図を配る前に起動が止まる */
    private val privateKey: PrivateKey = readPkcs8(privateKeyPem)

    private val utilities = CloudFrontUtilities.create()

    override fun sign(key: String): URI {
        val request = CannedSignerRequest.builder()
            .resourceUrl("${baseUrl.trimEnd('/')}/$key")
            .privateKey(privateKey)
            .keyPairId(keyPairId)
            .expirationDate(FigureUrlSigner.expiresAt(clock.instant()))
            .build()
        return URI.create(utilities.getSignedUrlWithCannedPolicy(request).url())
    }

    private companion object {
        fun readPkcs8(pem: String): PrivateKey {
            val body = pem.lineSequence()
                .map(String::trim)
                .filterNot { it.startsWith("-----") }
                .joinToString("")
            require(body.isNotEmpty()) { "図の署名の秘密鍵がありません（FIGURES_CLOUDFRONT_PRIVATE_KEY）" }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        }
    }
}

/**
 * S3 の署名付き URL。ローカル（LocalStack）で使う。CloudFront がないため、応答ヘッダ（CSP）は付かない
 */
class S3PresignedFigureUrlSigner(
    private val presigner: S3Presigner,
    private val bucket: String,
    private val clock: Clock,
) : FigureUrlSigner {

    override fun sign(key: String): URI {
        val now = clock.instant()
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.between(now, FigureUrlSigner.expiresAt(now)))
            .getObjectRequest { it.bucket(bucket).key(key) }
            .build()
        return presigner.presignGetObject(request).url().toURI()
    }
}

/**
 * ブラウザが画像を S3 へ直接上げる、署名付き PUT の URL を作る（ADR-0020）。上げる先は検査の前の置き場所で、誰にも配らない。
 *
 * AWS SDK for Java v2 は、大きさの範囲を条件にできる署名付き POST を作れない。
 * 代わりに、種類（Content-Type）と大きさ（Content-Length）を署名に含める。申告と違う本体は、S3 が署名の不一致で拒む。
 * 大きさは、完了を受けたときにも確かめる。
 */
class UploadUrlSigner(private val presigner: S3Presigner, private val bucket: String) {

    fun sign(key: String, contentType: String, size: Long): URI {
        val request = PutObjectPresignRequest.builder()
            .signatureDuration(EXPIRES_IN)
            .putObjectRequest { it.bucket(bucket).key(key).contentType(contentType).contentLength(size) }
            .build()
        return presigner.presignPutObject(request).url().toURI()
    }

    companion object {
        val EXPIRES_IN: Duration = Duration.ofMinutes(5)
    }
}
