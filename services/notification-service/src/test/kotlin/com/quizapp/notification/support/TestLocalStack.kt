package com.quizapp.notification.support

import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.regions.Region

/**
 * テストで共有する LocalStack。クラスごとに起動すると、そのたびに十数秒かかる。
 * 止めるのは Testcontainers（Ryuk）に任せる。版は docker-compose.yml と揃える
 */
object TestLocalStack {
    private val container: LocalStackContainer by lazy {
        LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"))
            .withServices("ssm", "dynamodb", "events")
            .apply { start() }
    }

    fun <B : AwsClientBuilder<B, C>, C> client(builder: B): C = builder
        .endpointOverride(container.endpoint)
        .region(Region.of(container.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(container.accessKey, container.secretKey)),
        )
        .build()
}
