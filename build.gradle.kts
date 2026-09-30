// Kotlin の Gradle プラグインは、ルートで 1 度だけ読み込む。
// サブプロジェクトごとに版を書くと、プロジェクトごとに別のクラスローダーで読み込まれ、
// プロジェクトをまたぐ依存（libs → services）が壊れることがある。版はここで持ち、各プロジェクトは版を書かずに適用する
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// コンテナイメージのビルドで、ソースより先に bootJar に要る依存を取っておく（services/quiz-service/Dockerfile、DEV-87）。
// 取ったものがレイヤーに残り、ソースだけを変えたときに取り直さない。
// `dependencies` タスクは依存の木を解くだけで、jar は取らない。
// lint とテストの依存は取らない。イメージのビルドでは使わず、キャッシュが大きくなるだけ。
//
// bootJar は、依存する libs/ のプロジェクトもコンパイルする。そのコンパイラの依存も要るため、プロジェクトごとに持つ
subprojects {
    tasks.register("downloadDependencies") {
        description = "bootJar に要る依存を取得する"
        val classpaths =
            setOf(
                "compileClasspath",
                "annotationProcessor",
                "productionRuntimeClasspath",
                "kotlinCompilerClasspath",
                "kotlinCompilerPluginClasspathMain",
                "kotlinBuildToolsApiClasspath",
            )
        // 取るのは外部の依存だけ。同じリポジトリのプロジェクト（libs/）はソースからビルドする
        val artifacts =
            files(
                configurations.matching { it.name in classpaths }.map { configuration ->
                    configuration.incoming
                        .artifactView { componentFilter { it is ModuleComponentIdentifier } }
                        .files
                },
            )
        doLast { artifacts.files }
    }
}
