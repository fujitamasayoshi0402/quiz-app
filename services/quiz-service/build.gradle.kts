plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    // 整形の規約。.editorconfig をそのまま読むので、設定を二重に持たない
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    // 静的解析。整形は ktlint、設計の匂いは detekt と役割を分ける
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

ktlint {
    version = "1.8.0"
}

detekt {
    // 既定のルールに乗せて、合わないところだけ config で上書きする
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
    // detekt 1.23.8 は Kotlin 2.0 でコンパイルされており、2.3 のままでは起動を拒否する。
    // detekt 専用のクラスパスだけ 2.0 系に固定する。
    // このとき既定のクラスパスごと置き換わるため、CLI 本体も明示する必要がある
    detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
    detekt("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Kotlin の data class をデシリアライズするために必要。
    // 入れないと**デフォルト引数が効かず**、省略可能なはずのフィールドを省いた JSON で失敗する。
    // Jackson 3 系では groupId が tools.jackson に変わっている
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Spring Boot 4 では autoconfigure がモジュール分割されたため、
    // flyway-core だけでは自動設定が効かない。starter が必要
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Flyway 10 以降、PostgreSQL のサポートは別モジュールに分かれている
    implementation("org.flywaydb:flyway-database-postgresql")

    // OpenAPI 定義をコードから生成する（ADR-0010）。
    // 3.x が Spring Boot 4 系の対応版。2.x は Boot 3 までなので上げられない
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 では MockMvc のテスト支援が spring-boot-starter-test から分離されている
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Testcontainers 2.x で artifact 名が変わっている（postgresql → testcontainers-postgresql）。
    // バージョンは Spring Boot の BOM が管理する
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

kotlin {
    compilerOptions {
        // プラットフォーム型を許容せず、Java 側の null 許容を厳格に扱う
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
