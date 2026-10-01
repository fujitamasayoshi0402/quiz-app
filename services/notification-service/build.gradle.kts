// クイズのイベントを受けて、テナントの Slack に知らせる Lambda（ADR-0022）。
// Spring は使わない。Lambda は呼ばれるたびに 1 件を処理するだけで、起動を軽くしたい。
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

    // 受けるイベントの型（ADR-0022）。quiz-service と共有する
    implementation(project(":libs:quiz-events"))

    implementation("com.amazonaws:aws-lambda-java-core:1.4.0")

    // イベントを共有の型に読む。版は quiz-service（Spring Boot が持つ版）と揃える
    implementation(platform("tools.jackson:jackson-bom:3.1.5"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // jackson-module-kotlin が引く版は古い。Kotlin の標準ライブラリと版を揃える（版はルートの Kotlin のプラグインが決める）
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // テナントの Webhook の URL（SSM Parameter Store）と、重複を捨てる記録（DynamoDB）。
    // HTTP の実装は JDK の URLConnection を使うものだけを入れる。Apache と Netty は成果物を大きくし、起動も遅くする（下の exclude）
    implementation(platform("software.amazon.awssdk:bom:2.55.4"))
    implementation("software.amazon.awssdk:ssm")
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:url-connection-client")
    // SDK は SLF4J でログを出す。実装がないと、呼ばれるたびに警告が 3 行ログに出る。SDK のログは使わないため捨てる
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-localstack")
    // ルールの絞り込みを、Terraform が使うものと同じ JSON で確かめる
    testImplementation("software.amazon.awssdk:eventbridge")
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

// Lambda に載せる zip。クラスを直下に、依存の jar を lib/ に置く（Java のマネージドランタイムが読む形）。
// fat jar にしないのは、依存の jar の署名やサービスの登録（META-INF）を混ぜて壊さないため
val buildZip by tasks.registering(Zip::class) {
    description = "Lambda に載せる zip を作る（build/distributions/notification-service.zip）"
    group = "build"
    archiveFileName = "notification-service.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(tasks.compileKotlin, tasks.processResources)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}
