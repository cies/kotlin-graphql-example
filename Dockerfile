FROM eclipse-temurin:24-jdk AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts settings.gradle.kts gradle.properties openapi.json ./
COPY docs docs
COPY src src

# Fail fast before Gradle download/bootstrap (runtime evidence-friendly).
RUN test -s /app/openapi.json \
  && test -f /app/src/main/kotlin/dropnext/dss/lib/dss/DssAppConfig.kt \
  || { echo >&2 '[dss-docker] openapi.json missing or Kotlin sources truncated (restore src/main/kotlin and commit).'; exit 1; }

RUN chmod +x gradlew && ./gradlew --no-daemon installDist -x test
RUN set -eux; \
    install_dir="$(ls -d /app/build/install/* | head -n 1)"; \
    app_name="$(basename "$install_dir")"; \
    cp -R "$install_dir" /app/dist; \
    printf '%s' "$app_name" > /app/dist/.app_name

FROM eclipse-temurin:24-jre AS runtime
WORKDIR /app

ENV PORT=9999

RUN useradd --create-home --shell /bin/bash appuser
COPY --from=build /app/dist/ /app/
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 9999
ENTRYPOINT ["/bin/sh","-c","exec /app/bin/$(cat /app/.app_name)"]
