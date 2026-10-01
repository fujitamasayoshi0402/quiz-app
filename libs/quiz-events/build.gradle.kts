// quiz-service が送り、notification-service が受けるイベントの型（ADR-0022）。
// **型だけを持つ。** JSON の読み書きはそれぞれのサービスが持ち、ここはライブラリに依存しない。
// 形の互換は、docs/events/ の見本を送る側と受ける側のテストがそれぞれ確かめる
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
}
