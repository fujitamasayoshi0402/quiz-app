# syntax=docker/dockerfile:1
#
# quiz-service のコンテナイメージ。ビルドコンテキストはリポジトリのルート。
#   docker build -f services/quiz-service/Dockerfile .
#
# ローカル（docker compose）と、Phase 2 の ECS で同じイメージを使う。環境の違いは環境変数で渡す。

# --- ビルド ------------------------------------------------------------------
# Java のメジャーバージョンは .mise.toml と揃える
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# 依存の解決をソースのコピーより先に行う。ソースだけを変えたときに、依存のレイヤーを使い回せる
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY services/quiz-service/build.gradle.kts services/quiz-service/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:quiz-service:dependencies --no-daemon --quiet > /dev/null

COPY services/quiz-service/src services/quiz-service/src
# テストは CI で回している。イメージのビルドでは Docker を使う Testcontainers も動かせない
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:quiz-service:bootJar --no-daemon --quiet

# 依存・ローダー・アプリケーションを層に分けて取り出す。
# アプリケーションのコードだけを変えたとき、依存の層（大きい）を送り直さずに済む
RUN java -Djarmode=tools -jar services/quiz-service/build/libs/quiz-service.jar \
    extract --layers --launcher --destination extracted

# --- 実行 --------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# root で動かさない
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# 変わりにくい順に重ねる
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080
# コンテナのメモリ上限に合わせてヒープを決める。既定（物理メモリの 1/4）では小さすぎる
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
