# syntax=docker/dockerfile:1

# ============================================================
# ビルド
# ============================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 依存の解決だけを先に済ませてレイヤに焼く。
# src だけ変えた再ビルドで、依存のダウンロードをやり直さない
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src

# **テストは走らせない。** 結合テストは Testcontainers が PostgreSQL を
# 起動するため Docker を要求し、ビルドコンテナの中では動かせない。
# 「テストを通った成果物か」はここでは保証されないので、
# **CI が green のコミットをデプロイする**（docs/runbook-deploy.md「前提」）。
RUN ./gradlew --no-daemon bootJar -x test

# ============================================================
# 実行
# ============================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# root で動かさない
RUN useradd --system --uid 10001 --create-home hatenacal
USER hatenacal

COPY --from=build --chown=hatenacal:hatenacal /src/build/libs/*.jar app.jar

# fly.toml の internal_port と application.yml の ${PORT:8080} に合わせる
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
