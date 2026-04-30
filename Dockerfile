FROM eclipse-temurin:24-jdk AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts gradle.properties ./
COPY src src

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
