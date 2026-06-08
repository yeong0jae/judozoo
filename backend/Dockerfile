# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 캐싱: 빌드 스크립트/래퍼 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 jar 빌드
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# --- runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
