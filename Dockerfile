# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the dropnext-shopify-service (DSS).
# Targets linux/arm64 (AWS Graviton). Build with:
#
#   docker buildx build --platform linux/arm64 \
#     --build-arg VERSION_TAG=$(git rev-parse --short HEAD) \
#     -t dropnext-shopify-service:$(git rev-parse --short HEAD) \
#     --load .
#
# The final image runs a jlinked custom JRE built from Amazon Corretto 25
# on top of amazonlinux:2023-minimal (~50–80MB total).


# ---------- Stage 1: Gradle build ----------
# Corretto 25 on Amazon Linux 2023 — multi-arch (amd64 + arm64).
FROM amazoncorretto:25-al2023 AS build

# Tools Gradle needs at runtime: tar/gzip for plugin downloads, findutils for the
# script wiring, git so VERSION_TAG can come from a build arg without surprising Gradle.
RUN dnf install -y --setopt=install_weak_deps=False tar gzip findutils \
  && dnf clean all

WORKDIR /src

# Prime the Gradle dependency cache. Copy only build configuration first so the cache layer
# is reused as long as build files do not change. The OpenAPI spec is required at configure
# time because `openApiGenerate` resolves its input spec when the build script is evaluated
# (see build.gradle.kts:openApiSpecFile) — copy just that one file from `src/resources/`
# so the rest of `src/` can land in a later, less frequently invalidated layer.
COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src/resources/monolith-dss-openapi.json ./src/resources/monolith-dss-openapi.json

# Resolve dependencies into the BuildKit cache mount. `--no-daemon` because containers.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
  chmod +x gradlew && ./gradlew --no-daemon --no-configuration-cache help

# Now bring in the actual sources. Tests are intentionally not copied — they are excluded
# via `.dockerignore` and the build runs `installDist -x test`. CI runs the suite separately.
# `src/graphql-schema/schema.graphql` is committed, so no introspection is needed at build time.
COPY src ./src

# Produce the runnable distribution at build/install/dropnext-shopify-service/{bin,lib}.
# Tests are skipped here — run them in CI before building the image.
# VERSION_TAG flows directly into the runtime stage as an ENV (see Stage 3).
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
  ./gradlew --no-daemon --no-configuration-cache installDist -x test


# ---------- Stage 2: jlink a custom JRE ----------
# Same Corretto base so the produced JRE matches the runtime libc (glibc on AL2023).
# Only depends on the JDK's bundled jmods — no need to read the app classpath.
FROM amazoncorretto:25-al2023 AS jlink

# binutils provides `objcopy`, required by `jlink --strip-debug`.
RUN dnf install -y --setopt=install_weak_deps=False binutils && dnf clean all

# Explicit JDK module list rather than `jdeps --print-module-deps`. jdeps fails in mixed
# modular/non-modular classpaths (Ktor + OkHttp + graphql-kotlin pull in plain JARs that
# `--ignore-missing-deps` does not fully cover).
#
# This list is the conservative superset for "Ktor server + HTTP client + Graphql + JSON":
#   java.base            — implicit, always present
#   java.instrument      — agents (incl. logging frameworks that probe for them)
#   java.logging         — j.u.l → SLF4J bridge
#   java.management      — JMX, MaxRAMPercentage relies on it for container memory detection
#   java.naming          — JNDI (referenced by some HTTPS/proxy detection paths)
#   java.net.http        — JDK HttpClient (transitive fallback in some libs)
#   java.xml             — Logback parses `logback.xml` via SAX at JVM startup
#   jdk.charsets         — non-default charsets (logback file appenders, etc.)
#   jdk.crypto.cryptoki  — PKCS11 (TLS keystore types)
#   jdk.crypto.ec        — TLS elliptic-curve ciphers (HTTPS clients: Shopify Admin, monolith)
#   jdk.naming.dns       — DNS lookups via JNDI
#   jdk.unsupported      — sun.misc.Unsafe (OkHttp/Netty and many performance-sensitive libs)
#   jdk.zipfs            — zip:// NIO filesystem (read jars/resources)
RUN jlink \
  --add-modules java.base,java.instrument,java.logging,java.management,java.naming,java.net.http,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.naming.dns,jdk.unsupported,jdk.zipfs \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=zip-9 \
  --output /javaruntime


# ---------- Stage 3: Runtime ----------
# AL2023 minimal is ~40MB and ships microdnf for installing the few extras we need.
# This tag only exists on Amazon ECR Public — Docker Hub has `amazonlinux:2023` (full,
# ~150MB) and `amazonlinux:minimal` (rolling), but not the pinned `2023-minimal` combo.
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal AS runtime

# curl-minimal: the HEALTHCHECK probe.
# findutils: provides `xargs`, required by the SSM agent that ECS Exec injects
#   when `enableExecuteCommand` is set on the task. Without it, exec sessions fail
#   with "xargs is not available (exit 1)".
# shadow-utils: useradd. Removed after creating the user to keep the image lean.
RUN microdnf install -y --setopt=install_weak_deps=0 curl-minimal findutils shadow-utils \
  && useradd -r -u 1000 -d /app -s /sbin/nologin dropnext \
  && microdnf remove -y shadow-utils \
  && microdnf clean all \
  && rm -rf /var/cache/yum /var/cache/dnf

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jlink /javaruntime ${JAVA_HOME}
COPY --from=build --chown=dropnext:dropnext /src/build/install/dropnext-shopify-service /app

# Forwarded into the running JVM. MaxRAMPercentage lets the JVM size its heap from the
# container memory limit. Headless avoids accidental AWT init.
ARG VERSION_TAG=unspecified
ENV VERSION_TAG=${VERSION_TAG} \
  PORT=9999 \
  JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.awt.headless=true"

USER dropnext
WORKDIR /app
EXPOSE 9999

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:9999/health || exit 1

ENTRYPOINT ["/app/bin/dropnext-shopify-service"]
