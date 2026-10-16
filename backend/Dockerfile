# --- build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# 의존성 캐싱: 빌드 스크립트 먼저 복사해 소스 변경 시 재다운로드를 피한다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null

COPY src ./src
RUN ./gradlew bootJar --no-daemon -q

# --- runtime stage ---
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
# compose healthcheck가 curl을 쓴다. 베이스 이미지에 없다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# 컨테이너 메모리 한도(compose `mem_limit`)의 비율로 힙을 잡는다. 남는 몫은 메타스페이스·스레드 스택이다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

COPY --from=build /app/build/libs/app.jar ./app.jar

EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]
