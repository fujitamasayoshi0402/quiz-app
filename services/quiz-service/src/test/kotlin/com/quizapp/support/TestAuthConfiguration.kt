package com.quizapp.support

import com.nimbusds.jwt.SignedJWT
import com.quizapp.auth.AccessTokens
import com.quizapp.auth.AuthProperties
import com.quizapp.auth.UserProfile
import com.quizapp.auth.UserProfiles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/**
 * テストでは Cognito につながない。[TestJwt] の鍵で署名したトークンを受け付ける。
 *
 * `@TestConfiguration` にしないのは、コンポーネントスキャンで拾わせるため。
 * `@SpringBootTest` のすべてのテストと、自分で起動するテスト（MigrationProfileTest）の両方に効く。
 */
@Configuration
class TestAuthConfiguration {

    /** 署名の鍵だけを差し替える。検証の条件は本物と同じ */
    @Bean
    @Primary
    fun testAccessTokenDecoder(properties: AuthProperties): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(TestJwt.publicKey).build().apply {
            setJwtValidator(AccessTokens.validator(properties))
        }

    /** Cognito の GetUser の代わりに、トークンに入れたメールアドレスを返す。入っていなければ未確認として扱う */
    @Bean
    @Primary
    fun testUserProfiles(): UserProfiles = object : UserProfiles {
        override fun fetch(accessToken: String) =
            UserProfile(email = SignedJWT.parse(accessToken).jwtClaimsSet.getStringClaim(TestJwt.EMAIL_CLAIM))
    }
}
