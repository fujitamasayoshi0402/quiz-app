// 公式の技術ニュースから、Bedrock でクイズの下書きを作り、S3 の受け箱に置く Lambda（ADR-0031）。
// notification-service と同じく Spring は使わない。1 日に 1 回呼ばれるだけで、起動を軽くしたい。
// Kotlin・ktlint・detekt の版は、ルートの build.gradle.kts が持つ
plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

ktlint {
    version = "1.8.0"
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
}

group = "com.quizapp"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // quiz-service と同じ理由で、detekt のクラスパスだけ Kotlin 2.0 系に固定する
    detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
    detekt("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    implementation("com.amazonaws:aws-lambda-java-core:1.4.0")

    // 下書きの JSON（docs/news-quizzes/draft.json）。版は quiz-service（Spring Boot が持つ版）と揃える
    implementation(platform("tools.jackson:jackson-bom:3.1.7"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Bedrock（Converse）と S3（受け箱と、読んだ発表の記録）。HTTP の実装は notification-service と同じく JDK のものだけ
    implementation(platform("software.amazon.awssdk:bom:2.55.4"))
    implementation("software.amazon.awssdk:bedrockruntime")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

configurations.all {
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Lambda に載せる zip。notification-service と同じ形（クラスを直下に、依存の jar を lib/ に）
val buildZip by tasks.registering(Zip::class) {
    description = "Lambda に載せる zip を作る（build/distributions/news-quiz-drafter.zip）"
    group = "build"
    archiveFileName = "news-quiz-drafter.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(tasks.compileKotlin, tasks.processResources)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}
