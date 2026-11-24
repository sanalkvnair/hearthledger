FROM gradle:jdk25 AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY src src
RUN gradle bootJar -x test --no-daemon -q

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
ARG APP_VERSION=dev
ARG GIT_SHA=
ENV APP_VERSION=$APP_VERSION GIT_SHA=$GIT_SHA
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
